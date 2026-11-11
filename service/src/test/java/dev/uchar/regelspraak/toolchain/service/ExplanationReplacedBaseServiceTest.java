package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.http.ServiceHttp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A subtype's own base replaces an inherited form-a base, and the exception groups of that base stay in the subtype's
 * stack as a group-default above the own base (uchar's RUNTIME-SEMANTICS ch. 7.10, block 3). The engine names that
 * group-default by the pointer of the replaced base, which in the document still carries its own justification and
 * consequence; the explanation names neither, and says of the inherited exception that it did not hold, or that it
 * decided with the own base displaced.
 *
 * <p>uchar's goldens {@code jutterswet-wrakbasis-vervangen-basis-regel} (the own base an ordinary rule) and
 * {@code -groep} (the own base a defaults group of one default), with the situation of their case: grondslag 10,00, W2
 * betwist.
 */
class ExplanationReplacedBaseServiceTest {

    private static final String DESCRIPTOR = """
        { "service": {"name":"Vervangen basis","version":"1"},
          "entrypoints": [ { "name":"uitkering","path":"uitkering",
            "scope": {"ruleNames": ["uitkering van een wrakrecht", "uitkering van een strandrecht"]},
            "input": ["Wrakrecht"], "output": ["Wrakrecht.uitkering"] } ],
          "parameters": {} }""";

    private static final String REQUEST = """
        { "instances": [
            { "type": "Wrakrecht", "id": "W1", "attributes": { "grondslag": 10.00 } },
            { "type": "Wrakrecht", "id": "W2", "attributes": { "grondslag": 10.00 },
              "characteristics": { "betwist": true } } ] }""";

    /** The inherited exception, nested in the replaced base, as the explanation names it. */
    private static final String EXCEPTION = "de variant op plaats 1 van uitzonderingsgroep 1 van de variant op plaats 1"
            + " van 'uitkering van een strandrecht'";

    /** W1 is not betwist: the own base decides, and the inherited exception above it did not hold. W2 is betwist: the
     *  inherited exception decides, and the own base is displaced. The own consequence of the replaced base (de
     *  grondslag) takes no part and is not named. */
    private static List<String> expected(String ownBase) {
        return List.of(
                "W1 heeft als uitkering 20,00 volgens " + ownBase + " (onvoorwaardelijk): zijn grondslag maal 2, met"
                        + " zijn grondslag (10,00); " + EXCEPTION + " gold niet, want niet geldt: het onderwerp"
                        + " betwist is",
                "W2 heeft als uitkering 99,00 volgens " + EXCEPTION + ": de waarde, omdat het onderwerp betwist is;"
                        + " verdrongen: " + ownBase + " (onvoorwaardelijk, 20,00)");
    }

    @Test
    void the_own_base_as_a_rule_stands_below_the_inherited_exception() throws Exception {
        assertEquals(expected("de regel 'uitkering van een wrakrecht'"),
                explanation("jutterswet-wrakbasis-vervangen-basis-regel"));
    }

    @Test
    void the_own_base_as_a_group_stands_below_the_inherited_exception() throws Exception {
        assertEquals(expected("de variant op plaats 1 van 'uitkering van een wrakrecht'"),
                explanation("jutterswet-wrakbasis-vervangen-basis-groep"));
    }

    private static List<String> explanation(String golden) throws Exception {
        String document = Files.readString(Path.of("../uchar/corpus/golden/" + golden + "/ir.json"));
        DecisionCore core = DecisionCore.buildFromDocument(DESCRIPTOR, document, CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader(), null);
        JsonNode answer = core.process("uitkering", DescriptorLoader.MAPPER.readTree(REQUEST),
                ServiceHttp.options("explanationParts=true"));
        ExplanationPartsCheck.assertPartsAreTheSentences(answer.get("explanation"), answer.get("explanationParts"));
        List<String> sentences = new ArrayList<>();
        for (JsonNode s : answer.get("explanation")) sentences.add(s.asText());
        return sentences;
    }
}
