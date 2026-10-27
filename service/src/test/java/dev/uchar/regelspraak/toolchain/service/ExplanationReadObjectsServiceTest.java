package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;
import dev.uchar.regelspraak.toolchain.service.http.ServiceHttp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Card explanation-read-objects-and-displaced, part 1: every {@code NAME} and {@code QUOTE} with the keys of an
 * attribute or a characteristic that names the value of one object says of which object, as {@code object}, the label
 * as {@code SUBJECT} writes it, so that a consumer finds the sentence of (object, {@code canonical}): the head of each
 * sentence, an own member, a read over a role of one hop and of two, a characteristic check over a role. A read over a
 * role whose link is absent has {@code object: null}; the text and the members of a fold over a set, which is read per
 * member, carry no {@code object}, and neither does a role. The plain sentences do not change.
 *
 * <p>Both starts of the service: from sources and from a {@code uchar-ir-v1} document.
 */
class ExplanationReadObjectsServiceTest {

    private static final String SOURCE = """
        Objecttype het Recht (mv: Rechten)
            het maandloon Numeriek (getal met 2 decimalen);
            de herlevingsgrens Numeriek (getal met 2 decimalen);
            het totaal inkomen Numeriek (getal met 2 decimalen);
            is herleefd kenmerk (bijvoeglijk);

        Objecttype het Dagloon (mv: Daglonen)
            de maximeringsfactor Numeriek (getal);

        Objecttype het Maandinkomen (mv: Maandinkomens)
            het inkomen Numeriek (getal met 2 decimalen);
            de herlevingsgrens overschreden kenmerk (bezittelijk);
            is gedekt kenmerk (bijvoeglijk);
            is meetellend kenmerk (bijvoeglijk);

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
                De herlevingsgrens van een Recht moet berekend worden als (zijn maandloon maal 0,875) naar beneden afgerond op 2 decimalen
                indien zijn maandloon gevuld is.

        Regel herleefd
            geldig altijd
                Een Recht is herleefd
                indien zijn maandloon gevuld is.

        Regel totaal inkomen
            geldig altijd
                Het totaal inkomen van een Recht moet berekend worden als de som van het inkomen van alle maandinkomens van het Recht.

        Regel maximeringsfactor
            geldig altijd
                De maximeringsfactor van een Dagloon moet gesteld worden op 1.

        Regel herlevingsgrens overschreden
            geldig altijd
                Een Maandinkomen heeft de herlevingsgrens overschreden
                indien zijn inkomen groter is dan de herlevingsgrens van het recht van het Maandinkomen.

        Regel gedekt
            geldig altijd
                Een Maandinkomen is gedekt
                indien de maximeringsfactor van het dagloon van het recht van het Maandinkomen gevuld is.

        Regel meetellend
            geldig altijd
                Een Maandinkomen is meetellend
                indien het recht van het Maandinkomen herleefd is.
        """;

    private static final String DESCRIPTOR = """
        { "service": {"name":"Objecten bij gelezen waarden","version":"1"},
          "entrypoints": [ { "name":"maandinkomen","path":"maandinkomen",
            "scope": {"ruleNames": ["herlevingsgrens", "herleefd", "totaal inkomen", "maximeringsfactor",
                                    "herlevingsgrens overschreden", "gedekt", "meetellend"]},
            "input": ["Maandinkomen"], "output": ["Maandinkomen:herlevingsgrens overschreden"] } ],
          "parameters": {} }""";

    /** R1 has a maandloon and a dagloon D1; R2 has neither. M1 and M2 are of R1, M3 of R2, and M4 of no recht: M3
     *  reads the empty herlevingsgrens on R2, and finds no dagloon of R2; M4 reaches no recht. */
    private static final String REQUEST = """
        { "instances": [
            { "type": "Recht", "id": "R1", "attributes": { "maandloon": 3000.00 } },
            { "type": "Dagloon", "id": "D1", "roles": { "recht": "R1" } },
            { "type": "Recht", "id": "R2" },
            { "type": "Maandinkomen", "id": "M1", "attributes": { "inkomen": 2800.01 }, "roles": { "recht": "R1" } },
            { "type": "Maandinkomen", "id": "M2", "attributes": { "inkomen": 1354.84 }, "roles": { "recht": "R1" } },
            { "type": "Maandinkomen", "id": "M3", "attributes": { "inkomen": 2800.00 }, "roles": { "recht": "R2" } },
            { "type": "Maandinkomen", "id": "M4", "attributes": { "inkomen": 2800.00 } } ] }""";

    /** Per sentence, by its subject and its head: each name with the keys of an attribute or a characteristic, as
     *  {@code text@object}, {@code text@null} for an absent link, or {@code text} without the key. */
    private static final List<String> EXPECTED = List.of(
            "R1 herleefd@R1: herleefd@R1 | zijn maandloon@R1",
            "R1 herlevingsgrens@R1: herlevingsgrens@R1 | zijn maandloon@R1 | zijn maandloon@R1",
            "R1 totaal inkomen@R1: totaal inkomen@R1 | de som van het inkomen van alle maandinkomens van het Recht",
            "D1 maximeringsfactor@D1: maximeringsfactor@D1",
            "R2 herleefd@R2: herleefd@R2 | zijn maandloon@R2",
            "R2 herlevingsgrens@R2: herlevingsgrens@R2 | zijn maandloon@R2",
            "R2 totaal inkomen@R2: totaal inkomen@R2 | de som van het inkomen van alle maandinkomens van het Recht",
            "M1 herlevingsgrens overschreden@M1: herlevingsgrens overschreden@M1 | zijn inkomen@M1"
                    + " | de herlevingsgrens van het recht van het Maandinkomen@R1",
            "M1 gedekt@M1: gedekt@M1 | de maximeringsfactor van het dagloon van het recht van het Maandinkomen@D1",
            "M1 meetellend@M1: meetellend@M1 | herleefd@R1",
            "M2 herlevingsgrens overschreden@M2: herlevingsgrens overschreden@M2 | zijn inkomen@M2"
                    + " | de herlevingsgrens van het recht van het Maandinkomen@R1",
            "M2 gedekt@M2: gedekt@M2 | de maximeringsfactor van het dagloon van het recht van het Maandinkomen@D1",
            "M2 meetellend@M2: meetellend@M2 | herleefd@R1",
            "M3 herlevingsgrens overschreden@M3: herlevingsgrens overschreden@M3 | zijn inkomen@M3"
                    + " | de herlevingsgrens van het recht van het Maandinkomen@R2",
            "M3 gedekt@M3: gedekt@M3 | de maximeringsfactor van het dagloon van het recht van het Maandinkomen@null",
            "M3 meetellend@M3: meetellend@M3 | herleefd@R2",
            "M4 herlevingsgrens overschreden@M4: herlevingsgrens overschreden@M4 | zijn inkomen@M4"
                    + " | de herlevingsgrens van het recht van het Maandinkomen@null",
            "M4 gedekt@M4: gedekt@M4 | de maximeringsfactor van het dagloon van het recht van het Maandinkomen@null",
            "M4 meetellend@M4: meetellend@M4 | herleefd@null");

    @Test
    void from_sources_every_read_value_names_its_object() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("maandinkomen.rgs", SOURCE);
        assertObjects(DecisionCore.assemble(DESCRIPTOR, sources, CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader()));
    }

    @Test
    void from_a_document_every_read_value_names_its_object() throws Exception {
        assertObjects(DecisionCore.buildFromDocument(DESCRIPTOR, UcharBuild.document(Resolver.resolve(SOURCE)),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader(), null));
    }

    private static void assertObjects(DecisionCore core) throws Exception {
        JsonNode answer = core.process("maandinkomen", DescriptorLoader.MAPPER.readTree(REQUEST),
                ServiceHttp.options("explanationParts=true"));
        ExplanationPartsCheck.assertPartsAreTheSentences(answer.get("explanation"), answer.get("explanationParts"));
        List<String> given = new ArrayList<>();
        for (JsonNode sentence : answer.get("explanationParts")) given.add(compact(sentence));
        assertEquals(String.join("\n", EXPECTED), String.join("\n", given));
    }

    /** The subject and the first name of a sentence, then every name with the keys of an attribute or a
     *  characteristic, in the order of the sentence. */
    private static String compact(JsonNode sentence) {
        List<String> names = new ArrayList<>();
        for (JsonNode p : sentence) names(p, names);
        return sentence.get(0).get("text").asText() + " " + names.get(0) + ": " + String.join(" | ", names);
    }

    private static void names(JsonNode part, List<String> sink) {
        for (JsonNode p : part.path("parts")) names(p, sink);
        String kind = part.get("kind").asText(), declaration = part.path("declaration").asText();
        if (!(kind.equals("NAME") || kind.equals("QUOTE"))
                || !(declaration.equals("ATTRIBUUT") || declaration.equals("KENMERK"))) return;
        String text = part.get("text").asText();
        if (!part.has("object")) sink.add(text);
        else sink.add(text + "@" + (part.get("object").isNull() ? "null" : part.get("object").asText()));
    }
}
