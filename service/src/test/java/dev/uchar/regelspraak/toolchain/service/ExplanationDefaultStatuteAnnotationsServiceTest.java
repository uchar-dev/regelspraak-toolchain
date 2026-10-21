package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;
import dev.uchar.regelspraak.toolchain.service.http.ServiceHttp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The ground of a default is the list of the {@code // Bron:} annotations of its block, as uchar puts it on the wire
 * ({@code Default.statuteAnnotations}, IR-SPEC ch. 6.1), and the parts parse each entry apart, as they do those of a
 * rule. The second annotation of 'uitsluiting bij dienstverband' holds a "; " inside a quotation, the separator the
 * sentence puts between two annotations too: the text alone could not tell the two apart. Both routes of the service,
 * from sources and from an IR document.
 */
class ExplanationDefaultStatuteAnnotationsServiceTest {

    private static final String SOURCE = """
        Objecttype de Jutter (mv: Jutters)
            is ingezetene kenmerk (bijvoeglijk);
            is in dienst kenmerk (bijvoeglijk);
            het recht op de penning kenmerk (bezittelijk);

        // Bron: BWBR0004045&artikel=2
        Regel penning
            geldig altijd
                Een Jutter heeft recht op de penning
                indien hij ingezetene is.

        // Bron: BWBR0004045&artikel=3&lid=1 · lemma's: uitsluiting
        // Bron: BWBR0004045&artikel=3&lid=2 · "met ingang van de dag van de dienst; ook bij uitzending"
        Uitzondering op penning: uitsluiting bij dienstverband
            geldig altijd
                Een Jutter heeft geen recht op de penning
                indien hij in dienst is.
        """;

    private static final String DESCRIPTOR = """
        { "service": {"name":"Grondslag van een default","version":"1"},
          "entrypoints": [ { "name":"penning","path":"penning",
            "scope": {"ruleNames": ["penning"]},
            "input": ["Jutter"], "output": ["Jutter:recht op de penning"] } ],
          "parameters": {} }""";

    private static final String REQUEST = """
        { "instances": [
            { "type": "Jutter", "id": "J1", "characteristics": { "ingezetene": true } },
            { "type": "Jutter", "id": "J2", "characteristics": { "ingezetene": true, "in dienst": true } } ] }""";

    private static final List<String> EXPECTED = List.of(
            "J1 heeft recht op de penning volgens de regel 'penning', omdat het onderwerp ingezetene is; 'uitsluiting"
                    + " bij dienstverband' gold niet, want niet geldt: het onderwerp in dienst is. Grondslag:"
                    + " BWBR0004045&artikel=2",
            "J2 heeft geen recht op de penning volgens 'uitsluiting bij dienstverband', omdat het onderwerp ingezetene"
                    + " is en het onderwerp in dienst is; verdrongen: de regel 'penning'. Grondslag:"
                    + " BWBR0004045&artikel=3&lid=1 · lemma's: uitsluiting; BWBR0004045&artikel=3&lid=2 · \"met ingang"
                    + " van de dag van de dienst; ook bij uitzending\"");

    /** The last part of each sentence, its ground. */
    private static final List<String> GROUNDS = List.of(
            "GROUND[«. Grondslag: » STATUTE{bwb=BWBR0004045, article=2}«BWBR0004045&artikel=2»]",
            "GROUND[«. Grondslag: » STATUTE{bwb=BWBR0004045, article=3, paragraph=1}«BWBR0004045&artikel=3&lid=1»"
                    + " « · lemma's: » LEMMA«uitsluiting» «; »"
                    + " STATUTE{bwb=BWBR0004045, article=3, paragraph=2}«BWBR0004045&artikel=3&lid=2» « · »"
                    + " NOTE«\"met ingang van de dag van de dienst; ook bij uitzending\"»]");

    private static DecisionCore fromSources() {
        return DecisionCore.assemble(DESCRIPTOR, Map.of("bron.rgs", SOURCE), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
    }

    private static DecisionCore fromDocument() {
        return DecisionCore.buildFromDocument(DESCRIPTOR, UcharBuild.document(Resolver.resolve(SOURCE)),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader(), null);
    }

    private static void assertGrounds(DecisionCore core) throws Exception {
        JsonNode answer = core.process("penning", DescriptorLoader.MAPPER.readTree(REQUEST),
                ServiceHttp.options("explanationParts=true"));
        List<String> sentences = new ArrayList<>();
        for (JsonNode z : answer.get("explanation")) sentences.add(z.asText());
        assertEquals(EXPECTED, sentences);
        ExplanationPartsCheck.assertPartsAreTheSentences(answer.get("explanation"), answer.get("explanationParts"));
        for (int i = 0; i < GROUNDS.size(); i++) {
            JsonNode parts = answer.get("explanationParts").get(i);
            JsonNode ground = DescriptorLoader.MAPPER.createArrayNode().add(parts.get(parts.size() - 1));
            assertEquals(GROUNDS.get(i), ExplanationPartsCheck.render(ground),
                    "zin " + i + ": " + EXPECTED.get(i));
        }
    }

    @Test
    void from_sources_the_ground_of_a_default_is_each_annotation_of_its_block() throws Exception {
        assertGrounds(fromSources());
    }

    @Test
    void from_a_document_the_ground_of_a_default_is_each_annotation_of_its_block() throws Exception {
        assertGrounds(fromDocument());
    }
}
