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
 * {@code ?explanation=true} on a characteristic assignment whose rule carries {@code // Bron:} annotations: the
 * sentence ends with ". Grondslag: <tekst>", the non-blank annotations in source order joined with "; ", as for a
 * default. A rule without annotations, or with a blank one, gets no Grondslag. Before, a rule never got one.
 *
 * <p>Both starts of the service: from sources the annotations come from the model the frontend resolved, which keeps
 * the blank annotation of 'aanmelding'; from a {@code uchar-ir-v1} document from what uchar's loader read, where the
 * emitter dropped it (WF-082). Both give the same sentences. The scenario route is pinned in
 * {@code ExplanationStatuteAnnotationTest}, with the same models and the same sentences.
 */
class ExplanationStatuteAnnotationServiceTest {

    private static final String SOURCE = """
        Objecttype de Jutter (mv: Jutters)
            de leeftijd Numeriek (geheel getal);
            is ingezetene kenmerk (bijvoeglijk);
            is meerderjarig kenmerk (bijvoeglijk);
            het recht op strandgoed kenmerk (bezittelijk);
            is aangemeld kenmerk (bijvoeglijk);

        // Bron: Jutterswet art. 2
        Regel meerderjarigheid
            geldig altijd
                Een Jutter is meerderjarig
                indien zijn leeftijd groter is dan 17.

        // Bron: Jutterswet art. 4 lid 1
        // Bron: Jutterswet art. 4 lid 3
        Regel recht op strandgoed
            geldig altijd
                Een Jutter heeft recht op strandgoed
                indien hij ingezetene is.

        //Bron:
        Regel aanmelding
            geldig altijd
                Een Jutter is aangemeld
                indien hij ingezetene is.
        """;

    private static final String DESCRIPTOR = """
        { "service": {"name":"Uitleg met grondslag","version":"1"},
          "entrypoints": [ { "name":"strandgoed","path":"strandgoed",
            "scope": {"ruleNames": ["meerderjarigheid", "recht op strandgoed", "aanmelding"]},
            "input": ["Jutter"],
            "output": ["Jutter:meerderjarig", "Jutter:recht op strandgoed", "Jutter:aangemeld"] } ],
          "parameters": {} }""";

    private static final String REQUEST = """
        { "instances": [
            { "type": "Jutter", "id": "J1", "attributes": { "leeftijd": 30 },
              "characteristics": { "ingezetene": true } },
            { "type": "Jutter", "id": "J2", "attributes": { "leeftijd": 16 } } ] }""";

    /** The sentences of {@code ExplanationStatuteAnnotationTest}, the scenario route. */
    private static final List<String> EXPECTED = List.of(
            "J1 is meerderjarig, omdat zijn leeftijd (30) is groter dan 17. Grondslag: Jutterswet art. 2",
            "J1 heeft recht op strandgoed, omdat het onderwerp ingezetene is. Grondslag: Jutterswet art. 4 lid 1;"
                    + " Jutterswet art. 4 lid 3",
            "J1 is aangemeld, omdat het onderwerp ingezetene is",
            "J2 is niet meerderjarig, want niet geldt: zijn leeftijd (16) is groter dan 17. Grondslag: Jutterswet"
                    + " art. 2",
            "J2 heeft geen recht op strandgoed, want niet geldt: het onderwerp ingezetene is. Grondslag: Jutterswet"
                    + " art. 4 lid 1; Jutterswet art. 4 lid 3",
            "J2 is niet aangemeld, want niet geldt: het onderwerp ingezetene is");

    /** The ordinary rule of a subtype as the base of the stack it inherits (RUNTIME-SEMANTICS ch. 7.10), with an
     *  annotation. */
    private static final String INHERITANCE = """
        Objecttype het Recht (mv: Rechten)
            de uitkering Numeriek (geheel getal);
            is betwist kenmerk (bijvoeglijk);
            is erkend kenmerk (bijvoeglijk);

        Objecttype het Wrakrecht is een Recht (mv: Wrakrechten)
            is gezonken kenmerk (bijvoeglijk);

        Regel uitkering
            geldig altijd
                De uitkering van een Recht moet gesteld worden op 10
                indien hij erkend is.

        Uitzondering op uitkering: betwiste uitkering
            geldig altijd
                De uitkering van een Recht moet gesteld worden op 99
                indien hij betwist is.

        // Bron: Jutterswet art. 9
        Regel uitkering van een wrakrecht
            geldig altijd
                De uitkering van een Wrakrecht moet gesteld worden op 200
                indien hij gezonken is.
        """;

    private static final String INHERITANCE_DESCRIPTOR = """
        { "service": {"name":"Uitleg van een stapel met grondslag","version":"1"},
          "entrypoints": [ { "name":"uitkering","path":"uitkering",
            "scope": {"ruleNames": ["uitkering", "uitkering van een wrakrecht"]},
            "input": ["Recht"], "output": ["Recht.uitkering", "Wrakrecht.uitkering"] } ],
          "parameters": {} }""";

    private static final String INHERITANCE_REQUEST = """
        { "instances": [
            { "type": "Wrakrecht", "id": "W1", "characteristics": { "gezonken": true } },
            { "type": "Wrakrecht", "id": "W2", "characteristics": { "erkend": true, "betwist": true } } ] }""";

    /** The sentences of {@code ExplanationStatuteAnnotationTest}, the scenario route. */
    private static final List<String> INHERITANCE_EXPECTED = List.of(
            "W1 heeft als uitkering 200 volgens de regel 'uitkering van een wrakrecht': 200, omdat het onderwerp"
                    + " gezonken is; 'betwiste uitkering' gold niet, want niet geldt: het onderwerp erkend is."
                    + " Grondslag: Jutterswet art. 9",
            "W2 heeft als uitkering 99 volgens 'betwiste uitkering': 99, omdat het onderwerp erkend is en het"
                    + " onderwerp betwist is; verdrongen: de regel 'uitkering van een wrakrecht'");

    @Test
    void from_sources_a_characteristic_assignment_names_the_statute_annotations_of_its_rule() throws Exception {
        assertEquals(EXPECTED, explanation(fromSources(SOURCE, DESCRIPTOR), "strandgoed", REQUEST));
    }

    @Test
    void from_a_document_a_characteristic_assignment_names_the_statute_annotations_of_its_rule() throws Exception {
        assertEquals(EXPECTED, explanation(fromDocument(SOURCE, DESCRIPTOR), "strandgoed", REQUEST));
    }

    @Test
    void from_sources_a_rule_that_decides_a_stack_names_its_statute_annotation() throws Exception {
        assertEquals(INHERITANCE_EXPECTED,
                explanation(fromSources(INHERITANCE, INHERITANCE_DESCRIPTOR), "uitkering", INHERITANCE_REQUEST));
    }

    @Test
    void from_a_document_a_rule_that_decides_a_stack_names_its_statute_annotation() throws Exception {
        assertEquals(INHERITANCE_EXPECTED,
                explanation(fromDocument(INHERITANCE, INHERITANCE_DESCRIPTOR), "uitkering", INHERITANCE_REQUEST));
    }

    private static DecisionCore fromSources(String source, String descriptor) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("jutters.rgs", source);
        return DecisionCore.assemble(descriptor, sources, CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
    }

    private static DecisionCore fromDocument(String source, String descriptor) {
        return DecisionCore.buildFromDocument(descriptor, UcharBuild.document(Resolver.resolve(source)),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader(), null);
    }

    private static List<String> explanation(DecisionCore core, String path, String request) throws Exception {
        JsonNode answer = core.process(path, DescriptorLoader.MAPPER.readTree(request), true);
        List<String> sentences = new ArrayList<>();
        for (JsonNode sentence : answer.get("explanation")) sentences.add(sentence.asText());
        return sentences;
    }
}
