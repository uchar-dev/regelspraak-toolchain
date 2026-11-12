package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.toolchain.service.http.ServiceHttp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A rule that deposits twice on one subject — a division by zero in its condition and in its value — stands once
 *  among the inconsistencies: uchar's §9.5 channel holds one fact per rule and subject, however often the rule
 *  deposits (RUNTIME-SEMANTICS ch. 5.6), and the answer and its fixpoint read that channel. */
class InconsistencyPerRuleAndSubjectTest {

    private static final String SOURCE = """
        Objecttype de Wacht (mv: Wachten)
            de ploegomvang Numeriek (geheel getal);
            de vangstwaarde Numeriek (geheel getal);
            de drempel Numeriek (geheel getal);
            het deel Numeriek (getal);

        Regel deel van een wacht
            geldig altijd
                Het deel van een Wacht moet gesteld worden op zijn vangstwaarde gedeeld door zijn ploegomvang
                indien hij aan ten minste één van de volgende voorwaarden voldoet:
                - zijn vangstwaarde gedeeld door zijn ploegomvang is groter dan 0
                - zijn drempel is groter dan 0.
        """;

    private static final String DESCRIPTOR = """
        { "service": { "name": "wacht", "version": "1" },
          "entrypoints": [ { "name": "deel", "path": "deel", "scope": { "ruleNames": ["deel van een wacht"] },
                             "input": ["Wacht"], "output": ["Wacht.deel"] } ],
          "parameters": {} }
        """;

    private static final String REQUEST = """
        { "instances": [ { "type": "Wacht", "id": "W1",
            "attributes": { "ploegomvang": 0, "vangstwaarde": 10, "drempel": 5 } } ] }
        """;

    @Test
    void two_deposits_of_one_rule_on_one_subject_are_one_inconsistency() throws Exception {
        DecisionCore core = DecisionCore.assemble(DESCRIPTOR, Map.of("bron.rgs", SOURCE), new CoreConfig(false, true),
                Thread.currentThread().getContextClassLoader());
        ObjectNode answer = core.process("deel", DescriptorLoader.MAPPER.readTree(REQUEST),
                ServiceHttp.options("fixpoint=true"));
        assertTrue(answer.at("/instances/0/attributes/deel").isNull(), answer.toString());
        assertEquals("[\"deel van een wacht\"]", answer.get("inconsistencies").toString());
        JsonNode details = answer.get("inconsistencyDetails");
        assertEquals(1, details.size(), details.toString());
        assertEquals("W1", details.at("/0/subject").asText(), details.toString());
        JsonNode fixpoint = answer.at("/fixpoint/inconsistencies");
        assertEquals("[{\"rule\":\"deel van een wacht\",\"subject\":\"W1\"}]", fixpoint.toString());
    }
}
