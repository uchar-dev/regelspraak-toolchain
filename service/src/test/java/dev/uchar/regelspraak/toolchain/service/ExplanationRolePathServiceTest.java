package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code ?explanation=true} on an operand that reads over a role, "de herlevingsgrens van het recht van het
 * Maandinkomen": the sentence names the value on the object the operand reaches, the one the engine derived. Before,
 * every such operand stood there as "(onbekend)", also where the engine had derived it, and the sentence of an
 * assigned characteristic said that it held because an unknown value was filled. Measured on a rule set of the
 * workbench (herleving-ww, scenario s01): five of the twenty sentences. Where the value is leeg, or no object is
 * reached, "(onbekend)" stays.
 *
 * <p>Both starts of the service: from sources the explanation runs on the model the frontend resolved, whose read
 * carries its written navigation, and the walk is the one uchar's emitter writes for it; from a {@code uchar-ir-v1}
 * document it runs on the model uchar's loader read, whose read carries its walk beside it, in the table the loader
 * returns. The scenario route is pinned in {@code ExplanationRolePathTest}.
 */
class ExplanationRolePathServiceTest {

    private static final String SOURCE = """
        Objecttype het Recht (mv: Rechten)
            het maandloon Numeriek (getal met 2 decimalen);
            de herlevingsgrens Numeriek (getal met 2 decimalen);

        Objecttype het Dagloon (mv: Daglonen)
            de maximeringsfactor Numeriek (getal);

        Objecttype het Maandinkomen (mv: Maandinkomens)
            het inkomen Numeriek (getal met 2 decimalen);
            de herlevingsgrens overschreden kenmerk (bezittelijk);

        Feittype maandinkomen van een recht
            het recht Recht
            het maandinkomen (mv: maandinkomens) Maandinkomen
        Eén recht heeft meerdere maandinkomens

        Feittype dagloon van een recht
            het recht Recht
            het dagloon Dagloon
        Eén recht heeft één dagloon

        Regel herlevingsgrens
            geldig altijd
                De herlevingsgrens van een Recht moet berekend worden als (het maandloon van het Recht maal 0,875)
                naar beneden afgerond op 2 decimalen
                indien zijn maandloon gevuld is.

        Regel maximeringsfactor
            geldig altijd
                De maximeringsfactor van een Dagloon moet gesteld worden op 1.

        Regel herlevingsgrens overschreden
            geldig altijd
                Een Maandinkomen heeft de herlevingsgrens overschreden
                indien hij aan alle volgende voorwaarden voldoet:
                - zijn inkomen is gevuld
                - de herlevingsgrens van het recht van het Maandinkomen is gevuld
                - zijn inkomen maal de maximeringsfactor van het dagloon van het recht van het Maandinkomen is groter
                dan de herlevingsgrens van het recht van het Maandinkomen.
        """;

    private static final String DESCRIPTOR = """
        { "service": {"name":"Uitleg over een rol","version":"1"},
          "entrypoints": [ { "name":"herlevingsgrens","path":"herlevingsgrens",
            "scope": {"ruleNames": ["herlevingsgrens", "maximeringsfactor", "herlevingsgrens overschreden"]},
            "input": ["Maandinkomen"], "output": ["Maandinkomen:herlevingsgrens overschreden"] } ],
          "parameters": {} }""";

    /** R1 has a herlevingsgrens of 2.625,00 and a dagloon D1 with factor 1; R2 has no maandloon, so no herlevingsgrens.
     *  M1 and M2 are of R1, M3 of R2, and M4 is of no recht. M1's inkomen has cents: on the model the loader read, a
     *  read carries no type, and a value stands at its own scale ("2.800" for 2800.00), which is the concern of the
     *  card verbalisator-getalliteral, not of this test. */
    private static final String REQUEST = """
        { "instances": [
            { "type": "Recht", "id": "R1", "attributes": { "maandloon": 3000.00 } },
            { "type": "Dagloon", "id": "D1", "roles": { "recht": "R1" } },
            { "type": "Recht", "id": "R2" },
            { "type": "Maandinkomen", "id": "M1", "attributes": { "inkomen": 2800.01 }, "roles": { "recht": "R1" } },
            { "type": "Maandinkomen", "id": "M2", "attributes": { "inkomen": 1354.84 }, "roles": { "recht": "R1" } },
            { "type": "Maandinkomen", "id": "M3", "attributes": { "inkomen": 2800.00 }, "roles": { "recht": "R2" } },
            { "type": "Maandinkomen", "id": "M4", "attributes": { "inkomen": 2800.00 } } ] }""";

    private static final List<String> EXPECTED = List.of(
            "M1 heeft de herlevingsgrens overschreden, omdat zijn inkomen (2.800,01) gevuld is en de herlevingsgrens van"
                    + " het recht van het Maandinkomen (2.625,00) gevuld is en zijn inkomen maal de maximeringsfactor van"
                    + " het dagloon van het recht van het Maandinkomen (2.800,01) is groter dan de herlevingsgrens van het"
                    + " recht van het Maandinkomen (2.625,00)",
            "M2 heeft geen herlevingsgrens overschreden, want niet geldt: zijn inkomen maal de maximeringsfactor van het"
                    + " dagloon van het recht van het Maandinkomen (1.354,84) is groter dan de herlevingsgrens van het"
                    + " recht van het Maandinkomen (2.625,00)",
            "M3 heeft geen herlevingsgrens overschreden, want niet geldt: de herlevingsgrens van het recht van het"
                    + " Maandinkomen (onbekend) gevuld is",
            "M4 heeft geen herlevingsgrens overschreden, want niet geldt: de herlevingsgrens van het recht van het"
                    + " Maandinkomen (onbekend) gevuld is");

    @Test
    void from_sources_an_operand_over_a_role_names_the_value_the_engine_derived() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("herleving.rgs", SOURCE);
        DecisionCore core = DecisionCore.assemble(DESCRIPTOR, sources, CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
        assertEquals(EXPECTED, explanation(core));
    }

    @Test
    void from_a_document_an_operand_over_a_role_names_the_value_the_engine_derived() throws Exception {
        DecisionCore core = DecisionCore.buildFromDocument(DESCRIPTOR, UcharBuild.document(Resolver.resolve(SOURCE)),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader(), null);
        assertEquals(EXPECTED, explanation(core));
    }

    private static List<String> explanation(DecisionCore core) throws Exception {
        JsonNode answer = core.process("herlevingsgrens", DescriptorLoader.MAPPER.readTree(REQUEST), true);
        List<String> sentences = new ArrayList<>();
        for (JsonNode sentence : answer.get("explanation")) sentences.add(sentence.asText());
        return sentences;
    }
}
