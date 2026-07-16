package io.github.yhavinga.regelspraak.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dev-only playground analyzer, exercised for its five answer shapes (the payload {@code POST
 * /playground/analyse} returns): a parse error (position + expected tokens), a resolver error, a clean model with
 * DRL text and its dataflow graaf, and a scenario outcome. These pin the core {@link Playground#analyseer}
 * contract; the wiring over HTTP (env-gated route) is covered by {@link PlaygroundHttpTest}.
 */
class PlaygroundTest {

    /** A clean mini-casus (model + two rules) — the DRL-ok shape. A boolean attribute is declared as a
     *  {@code kenmerk} (§13.3.2.1); the korting rule uses the {@code als … indien …} conditional-value form. */
    private static final String SCHOON = """
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
    private static final String MET_SCENARIO = SCHOON + """

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
    void parse_fout_geeft_positie_en_verwachte_tokens() {
        // A truncated objecttype header: the closing ')' / member list is missing, so the parser errors with a
        // position and a follow set.
        ObjectNode a = Playground.analyseer("Objecttype de Persoon (mv:");
        JsonNode parse = a.get("parse");
        assertFalse(parse.get("ok").asBoolean(), a.toString());
        assertTrue(parse.get("fouten").size() >= 1, a.toString());
        JsonNode f = parse.get("fouten").get(0);
        assertTrue(f.get("regel").asInt() >= 1, f.toString());
        assertTrue(f.has("kolom"), f.toString());
        assertTrue(f.get("verwacht").isArray(), f.toString());
        // a broken model has no DRL and no graaf
        assertFalse(a.has("drl"), "een parse-fout mag geen DRL opleveren: " + a);
        assertFalse(a.has("graaf"), "een parse-fout mag geen graaf opleveren: " + a);
    }

    // ---------------- shape 2: resolver error ----------------

    @Test
    void resolve_fout_geeft_resolver_diagnostiek() {
        // Parses fine, but the rule assigns a Tekst literal to a Numeriek attribute -> a typeringen ERROR.
        String bron = """
            Objecttype de Persoon (mv: Personen)
                de leeftijd Numeriek (geheel getal);
            Regel fout
            geldig altijd
            De leeftijd van een Persoon moet berekend worden als "twaalf".
            """;
        ObjectNode a = Playground.analyseer(bron);
        assertTrue(a.get("parse").get("ok").asBoolean(), "syntactisch geldig: " + a);
        JsonNode resolver = a.get("resolver");
        assertFalse(resolver.get("ok").asBoolean(), "type-mismatch hoort een resolver-fout te zijn: " + a);
        assertTrue(resolver.get("fouten").size() >= 1, a.toString());
        assertFalse(a.has("drl"), "een resolver-fout mag geen DRL opleveren: " + a);
        assertFalse(a.has("graaf"), "een resolver-fout mag geen graaf opleveren: " + a);
    }

    // ---------------- shape 3: DRL ok ----------------

    @Test
    void schoon_model_levert_drl_tekst() {
        ObjectNode a = Playground.analyseer(SCHOON);
        assertTrue(a.get("parse").get("ok").asBoolean(), a.toString());
        assertTrue(a.get("resolver").get("ok").asBoolean(), a.toString());
        assertTrue(a.has("drl"), "een schoon model hoort DRL te leveren: " + a);
        String drl = a.get("drl").asText();
        assertTrue(drl.contains("rule "), "de DRL hoort minstens één rule te bevatten: " + drl);
        // a clean model also yields a graaf: the dataflow projection, as bare Mermaid flowchart source carrying
        // the rule nodes (bepaal minderjarigheid writes the kenmerk, bepaal korting reads it and writes korting).
        assertTrue(a.has("graaf"), "een schoon model hoort een graaf te leveren: " + a);
        assertFalse(a.has("graafFout"), "geen graaf-fout verwacht bij een schoon model: " + a);
        String graaf = a.get("graaf").asText();
        assertTrue(graaf.startsWith("flowchart LR"), "de graaf is Mermaid-flowchart-bron: " + graaf);
        assertTrue(graaf.contains("bepaal minderjarigheid"), "regel-knoop ontbreekt: " + graaf);
        assertTrue(graaf.contains("bepaal korting"), "regel-knoop ontbreekt: " + graaf);
        // no scenario block -> no scenario section
        assertFalse(a.has("scenario"), "zonder Scenario-blok geen scenario-sectie: " + a);
    }

    // ---------------- shape 4: scenario outcome ----------------

    @Test
    void scenario_blok_levert_uitkomst_per_verwachting() {
        ObjectNode a = Playground.analyseer(MET_SCENARIO);
        assertTrue(a.get("resolver").get("ok").asBoolean(), a.toString());
        JsonNode scenario = a.get("scenario");
        assertTrue(scenario != null && scenario.has("resultaten"), "scenario-sectie verwacht: " + a);
        assertEquals(1, scenario.get("resultaten").size(), a.toString());
        JsonNode r = scenario.get("resultaten").get(0);
        assertEquals("PASS", r.get("status").asText(), a.toString());
        assertEquals(2, r.get("verwachtingen").size(), a.toString());
        for (JsonNode v : r.get("verwachtingen"))
            assertTrue(v.get("ok").asBoolean(), "elke verwachting hoort te slagen: " + v);
    }
}
