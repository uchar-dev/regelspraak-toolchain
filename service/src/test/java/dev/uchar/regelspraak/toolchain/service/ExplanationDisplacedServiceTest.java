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
 * Card explanation-read-objects-and-displaced, part 2: a variant of a defaults group after the decider says what it
 * found, from uchar's reading of a displaced default (RUNTIME-SEMANTICS ch. 5.12, "A displaced default is read"): "(gold,
 * &lt;value&gt;)" where its condition held and it gave a value, "(gold niet)" where it did not hold; its group
 * {@code VARIANT} carries {@code holds} and the value as a {@code VALUE}. Before, a displaced variant stood there by its
 * name alone.
 *
 * <p>uchar's golden {@code anchor/jutterswet-nestuitzondering}, case 1 (grondX and grondY): the exception group y
 * decides 10,00; the own content of the default that carries it (grondX, 40,00) held and is displaced; the two members
 * of the base's group (grondB1, grondB2) did not hold.
 */
class ExplanationDisplacedServiceTest {

    private static final String DESCRIPTOR = """
        { "service": {"name":"Verdrongen varianten","version":"1"},
          "entrypoints": [ { "name":"heffing","path":"heffing",
            "scope": {"ruleNames": ["heffing van een jutter"]},
            "input": ["Jutter"], "output": ["Jutter.heffing"] } ],
          "parameters": {} }""";

    private static final String REQUEST = """
        { "instances": [ { "type": "Jutter", "id": "M", "characteristics": { "grondX": true, "grondY": true } } ] }""";

    @Test
    void a_displaced_variant_says_whether_it_held_and_what_it_gave() throws Exception {
        String document = Files.readString(Path.of("../uchar/corpus/golden/anchor/jutterswet-nestuitzondering/ir.json"));
        DecisionCore core = DecisionCore.buildFromDocument(DESCRIPTOR, document, CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader(), null);
        JsonNode answer = core.process("heffing", DescriptorLoader.MAPPER.readTree(REQUEST),
                ServiceHttp.options("explanationParts=true"));
        ExplanationPartsCheck.assertPartsAreTheSentences(answer.get("explanation"), answer.get("explanationParts"));
        assertEquals(List.of(
                "VARIANT{decided=false, displaced=true, holds=true}[«; verdrongen: de variant op plaats 1 van '»"
                        + " RULE«heffing van een jutter» «' (gold, » VALUE«40,00» «)»]",
                "VARIANT{decided=false, displaced=true, holds=false}[«, de variant op plaats 1 van uitzonderingsgroep"
                        + " 1 van de variant op plaats 2 van '» RULE«heffing van een jutter» «' (gold niet)»]",
                "VARIANT{decided=false, displaced=true, holds=false}[«, de variant op plaats 1 van uitzonderingsgroep"
                        + " 2 van de variant op plaats 2 van '» RULE«heffing van een jutter» «' (gold niet)»]"),
                displaced(answer.get("explanationParts")));
    }

    /** Every displaced variant of every sentence, rendered. */
    private static List<String> displaced(JsonNode parts) {
        List<String> sink = new ArrayList<>();
        for (JsonNode sentence : parts)
            for (JsonNode p : sentence)
                if ("VARIANT".equals(p.path("kind").asText()) && p.path("displaced").asBoolean())
                    sink.add(ExplanationPartsCheck.render(DescriptorLoader.MAPPER.createArrayNode().add(p)));
        return sink;
    }
}
