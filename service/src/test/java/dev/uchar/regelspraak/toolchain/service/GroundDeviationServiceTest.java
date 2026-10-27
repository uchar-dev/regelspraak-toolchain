package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;
import dev.uchar.regelspraak.toolchain.service.http.ServiceHttp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Card ground-deviation: a deviation in the ground names the layers it lies between and the layer the rule set
 * follows ({@code deviatie wet/hb, volgt hb: <slug>}), or the one layer it lies within ({@code deviatie binnen brm:
 * <slug>}), as {@code between} and {@code follows} on its {@code DEVIATION}; a followed layer outside the pair is read
 * as written, and the old forms {@code afwijking: } and {@code afwijking, niet gevolgd: } are notes. The sentence and
 * the text of its parts are the annotations as written.
 *
 * <p>The model: the rule 'gevestigd' with four {@code // Bron:} lines, the three forms of the card, a followed layer
 * outside the pair and the two old forms; and the defaults group 'premie', whose exception 'jeugdpremie' carries a
 * deviation in the annotation of its block, so a default reads the same grammar.
 */
class GroundDeviationServiceTest {

    private static final String SOURCE = """
            Objecttype de Jutter (mv: Jutters)
                de leeftijd Numeriek (geheel getal);
                de premie Numeriek (getal met 2 decimalen);
                is ingezetene kenmerk (bijvoeglijk);
                is gevestigd kenmerk (bijvoeglijk);

            // Bron: BWBR0004045&artikel=2&lid=1 · lemma's: gevestigde-jutter · deviatie wet/hb, volgt hb: het-overlijden-als-alsof-uitsluitingsgrond
            // Bron: BWBR0004045&artikel=2&lid=2 · lemma's: ingezetene · deviatie wet/brm, volgt wet: de-herlevingsgrens-op-daglonen · deviatie binnen brm: de-bezwaartermijn-met-drie-getallen
            // Bron: BWBR0004045&artikel=2&lid=3 · lemma's: kust · deviatie wet/hb, volgt brm: een-laag-buiten-het-paar
            // Bron: BWBR0004045&artikel=2&lid=4 · lemma's: woonplaats · afwijking: de-oude-gevolgde · afwijking, niet gevolgd: de-oude-niet-gevolgde
            Regel gevestigd
                geldig altijd
                    Een Jutter is gevestigd
                    indien hij ingezetene is.

            // Bron: BWBR0004045&artikel=5&lid=1
            Regel premie
                geldig altijd
                    De premie van een Jutter moet gesteld worden op 100,00.

            // Bron: BWBR0004045&artikel=5&lid=2 · lemma's: jeugdpremie · deviatie hb/brm, volgt hb: de-jeugdgrens
            Uitzondering op premie: jeugdpremie
                geldig altijd
                    De premie van een Jutter moet gesteld worden op 50,00
                    indien zijn leeftijd kleiner is dan 18.
            """;

    private static final String DESCRIPTOR = """
            { "service": {"name":"Deviaties in de grondslag","version":"1"},
              "entrypoints": [ { "name":"jutters","path":"jutters",
                "scope": {"ruleNames": ["gevestigd", "premie"]},
                "input": ["Jutter"],
                "output": ["Jutter:gevestigd", "Jutter.premie"] } ],
              "parameters": {} }""";

    private static final String REQUEST = """
            { "asOfDate": "2026-01-01",
              "instances": [
                { "type": "Jutter", "id": "J1", "characteristics": { "ingezetene": true },
                  "attributes": { "leeftijd": 16 } } ] }""";

    private static final String GEVESTIGD_TEXT = ". Grondslag: BWBR0004045&artikel=2&lid=1 · lemma's: gevestigde-jutter"
            + " · deviatie wet/hb, volgt hb: het-overlijden-als-alsof-uitsluitingsgrond;"
            + " BWBR0004045&artikel=2&lid=2 · lemma's: ingezetene · deviatie wet/brm, volgt wet:"
            + " de-herlevingsgrens-op-daglonen · deviatie binnen brm: de-bezwaartermijn-met-drie-getallen;"
            + " BWBR0004045&artikel=2&lid=3 · lemma's: kust · deviatie wet/hb, volgt brm: een-laag-buiten-het-paar;"
            + " BWBR0004045&artikel=2&lid=4 · lemma's: woonplaats · afwijking: de-oude-gevolgde · afwijking, niet"
            + " gevolgd: de-oude-niet-gevolgde";

    private static final List<String> EXPECTED = List.of(
            "J1 is gevestigd, omdat het onderwerp ingezetene is" + GEVESTIGD_TEXT,
            "J1 heeft als premie 50,00 volgens 'jeugdpremie': 50,00, omdat zijn leeftijd (16) is kleiner dan 18;"
                    + " verdrongen: de regel 'premie' (onvoorwaardelijk, 100,00). Grondslag:"
                    + " BWBR0004045&artikel=5&lid=2 · lemma's: jeugdpremie · deviatie hb/brm, volgt hb: de-jeugdgrens");

    private static final String GEVESTIGD_GROUND = "GROUND{rule=gevestigd}[«. Grondslag: »"
            + " STATUTE{bwb=BWBR0004045, article=2, paragraph=1}«BWBR0004045&artikel=2&lid=1» « · lemma's: »"
            + " LEMMA«gevestigde-jutter» « · deviatie wet/hb, volgt hb: »"
            + " DEVIATION{between=[\"wet\",\"hb\"], follows=hb}«het-overlijden-als-alsof-uitsluitingsgrond» «; »"
            + " STATUTE{bwb=BWBR0004045, article=2, paragraph=2}«BWBR0004045&artikel=2&lid=2» « · lemma's: »"
            + " LEMMA«ingezetene» « · deviatie wet/brm, volgt wet: »"
            + " DEVIATION{between=[\"wet\",\"brm\"], follows=wet}«de-herlevingsgrens-op-daglonen»"
            + " « · deviatie binnen brm: » DEVIATION{between=[\"brm\"]}«de-bezwaartermijn-met-drie-getallen» «; »"
            + " STATUTE{bwb=BWBR0004045, article=2, paragraph=3}«BWBR0004045&artikel=2&lid=3» « · lemma's: »"
            + " LEMMA«kust» « · deviatie wet/hb, volgt brm: »"
            + " DEVIATION{between=[\"wet\",\"hb\"], follows=brm}«een-laag-buiten-het-paar» «; »"
            + " STATUTE{bwb=BWBR0004045, article=2, paragraph=4}«BWBR0004045&artikel=2&lid=4» « · lemma's: »"
            + " LEMMA«woonplaats» « · » NOTE«afwijking: de-oude-gevolgde» « · »"
            + " NOTE«afwijking, niet gevolgd: de-oude-niet-gevolgde»]";

    private static final String JEUGDPREMIE_GROUND = "GROUND{group=premie, pointer=/defaults/0, position=1,"
            + " variant=jeugdpremie}[«. Grondslag: » STATUTE{bwb=BWBR0004045, article=5, paragraph=2}"
            + "«BWBR0004045&artikel=5&lid=2» « · lemma's: » LEMMA«jeugdpremie» « · deviatie hb/brm, volgt hb: »"
            + " DEVIATION{between=[\"hb\",\"brm\"], follows=hb}«de-jeugdgrens»]";

    @Test
    void the_ground_reads_the_layers_of_a_deviation_and_the_old_forms_as_notes() throws Exception {
        DecisionCore core = DecisionCore.assemble(DESCRIPTOR, Map.of("bron.rgs", SOURCE), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
        ObjectNode answer = core.process("jutters", DescriptorLoader.MAPPER.readTree(REQUEST),
                ServiceHttp.options("explanationParts=true"));
        List<String> sentences = new ArrayList<>();
        for (JsonNode z : answer.get("explanation")) sentences.add(z.asText());
        assertEquals(EXPECTED, sentences);
        JsonNode parts = answer.get("explanationParts");
        ExplanationPartsCheck.assertPartsAreTheSentences(answer.get("explanation"), parts);
        assertEquals(2, ExplanationPartsCheck.assertGroundsNameTheirDeciders(parts,
                DescriptorLoader.MAPPER.readTree(UcharBuild.document(Resolver.resolve(SOURCE)))));
        assertEquals(List.of(GEVESTIGD_GROUND, JEUGDPREMIE_GROUND), List.of(ground(parts.get(0)), ground(parts.get(1))));
    }

    private static String ground(JsonNode sentence) {
        JsonNode last = sentence.get(sentence.size() - 1);
        return ExplanationPartsCheck.render(DescriptorLoader.MAPPER.createArrayNode().add(last));
    }
}
