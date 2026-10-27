package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.http.ServiceHttp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A characteristic that only a subtype declares, checked on an object of the supertype (uchar's narrowing host,
 * RUNTIME-SEMANTICS ch. 5.8b rule 4): the explanation names it as a {@code NAME} with {@code declaration: KENMERK} on
 * the subtype in the condition of a rule on the supertype, also for an object outside the subtype (S), and quotes it
 * with that declaration in the filter of a computed fold over a role the supertype fills. A fold that is an operand of
 * a comparison is quoted whole, as every such fold is (blokkade).
 *
 * <p>uchar's golden {@code jutterswet-ploegblokkade}, case 1: Jutrecht J (grens 10, loon 5), Wrakrecht W1 (loon 20,
 * zeeweken 6), Wrakrecht W2 (loon 30, zeeweken 2) and a bare Strandrecht S (loon 40), all of ploeg P, drempel 5. Only
 * W1 has the vaartijd.
 */
class ExplanationNarrowingHostServiceTest {

    private static final String DESCRIPTOR = """
        { "service": {"name":"Kenmerk van een subtype","version":"1"},
          "entrypoints": [ { "name":"ploeg","path":"ploeg",
            "scope": {"ruleNames": ["vaartijd", "zeewaardig", "hoogste vaarloon", "blokkade"]},
            "input": ["Strandrecht", "Ploeg", "Logboek"],
            "output": ["Strandrecht:zeewaardig", "Jutrecht.hoogste vaarloon", "Jutrecht:blokkade"] } ],
          "parameters": { "vaartijddrempel": 5 } }""";

    private static final String REQUEST = """
        { "instances": [
            { "type": "Ploeg", "id": "P" },
            { "type": "Jutrecht", "id": "J", "attributes": { "grens": 10.00, "loon": 5.00 },
              "roles": { "ploeg": "P" } },
            { "type": "Wrakrecht", "id": "W1", "attributes": { "loon": 20.00 }, "roles": { "ploeg": "P" } },
            { "type": "Wrakrecht", "id": "W2", "attributes": { "loon": 30.00 }, "roles": { "ploeg": "P" } },
            { "type": "Strandrecht", "id": "S", "attributes": { "loon": 40.00 }, "roles": { "ploeg": "P" } },
            { "type": "Logboek", "id": "L1", "attributes": { "zeeweken": 6 }, "roles": { "recht": "W1" } },
            { "type": "Logboek", "id": "L2", "attributes": { "zeeweken": 2 }, "roles": { "recht": "W2" } } ] }""";

    @Test
    void the_characteristic_of_a_subtype_is_named_where_the_supertype_is_checked() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("ploegblokkade.rgs",
                Files.readString(Path.of("../uchar/corpus/golden/jutterswet-ploegblokkade/source.rgs")));
        DecisionCore core = DecisionCore.assemble(DESCRIPTOR, sources, CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
        JsonNode answer = core.process("ploeg", DescriptorLoader.MAPPER.readTree(REQUEST),
                ServiceHttp.options("explanationParts=true"));
        ExplanationPartsCheck.assertPartsAreTheSentences(answer.get("explanation"), answer.get("explanationParts"));
        assertEquals(EXPECTED, vaartijd(answer.get("explanationParts")));
    }

    private static final String NAME =
            "NAME{declaration=KENMERK, owner=Wrakrecht, canonical=vaartijd, object=%s}«vaartijd»";
    private static final String FILTER =
            "QUOTE{declaration=KENMERK, owner=Wrakrecht, canonical=vaartijd}«hij heeft de vaartijd»";

    private static final List<String> EXPECTED = List.of(
            "SUBJECT«J» :: QUOTE«de maximale waarde van het loon van alle rechten van de ploeg van het Jutrecht die aan"
                    + " alle volgende voorwaarden voldoen: .. hij is niet het Jutrecht zelf .. het Strandrecht heeft de"
                    + " vaartijd»",
            "SUBJECT«J» :: " + NAME.formatted("J"),
            "SUBJECT«J» :: " + FILTER,
            "SUBJECT«W1» :: " + NAME.formatted("W1"),
            "SUBJECT«W1» :: QUOTE{declaration=PARAMETER, owner=null, canonical=vaartijddrempel}«de vaartijddrempel»",
            "SUBJECT«W1» :: " + NAME.formatted("W1"),
            "SUBJECT«W1» :: " + FILTER,
            "SUBJECT«W2» :: " + NAME.formatted("W2"),
            "SUBJECT«W2» :: QUOTE{declaration=PARAMETER, owner=null, canonical=vaartijddrempel}«de vaartijddrempel»",
            "SUBJECT«W2» :: " + NAME.formatted("W2"),
            "SUBJECT«W2» :: " + FILTER,
            "SUBJECT«S» :: " + NAME.formatted("S"));

    /** Every part that names the vaartijd, rendered, with the first part of its sentence. */
    private static List<String> vaartijd(JsonNode parts) {
        List<String> sink = new ArrayList<>();
        for (JsonNode sentence : parts) {
            String head = ExplanationPartsCheck.render(DescriptorLoader.MAPPER.createArrayNode().add(sentence.get(0)));
            collect(sentence, head, sink);
        }
        return sink;
    }

    private static void collect(JsonNode parts, String head, List<String> sink) {
        for (JsonNode p : parts) {
            if (p.has("parts")) collect(p.get("parts"), head, sink);
            else if ("vaartijd".equals(p.path("canonical").asText()) || p.path("text").asText().contains("vaartijd"))
                sink.add(head + " :: " + ExplanationPartsCheck.render(DescriptorLoader.MAPPER.createArrayNode().add(p)));
        }
    }
}
