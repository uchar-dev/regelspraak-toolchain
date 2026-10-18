package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The answer in the vocabulary a request chooses ({@link AnswerBuilder} with a
 * {@link dev.uchar.regelspraak.toolchain.engine.Naming}): the object type, the attributes and characteristics, an
 * enumeration value, the rule of an inconsistency and the explanation in its names; the descriptor's default per
 * entry point; the fallback on the canonical name of a declaration without a designation; the canonical answer as it
 * always was; and the start refused on a default vocabulary the model does not declare, or on a vocabulary named
 * {@code canonical}.
 */
class VocabularyAnswerTest {

    private static DecisionCore GOLDEN;
    private static DecisionCore AMBIGUOUS;

    @BeforeAll
    static void assemble() {
        GOLDEN = VocabularySource.core();
        AMBIGUOUS = VocabularySource.ambiguous();
    }

    private static JsonNode instance(ObjectNode answer, String id) {
        for (JsonNode i : answer.get("instanties")) if (id.equals(i.get("id").asText())) return i;
        throw new AssertionError("geen instantie " + id + " in " + answer);
    }

    @Test
    void the_answer_in_the_handbook() {
        ObjectNode a = GOLDEN.process("premie", VocabularySource.json(VocabularySource.CASE1),
                new RequestOptions(true, "handboek", null));
        JsonNode v1 = instance(a, "V1");
        assertEquals("aangespoeld voorwerp", v1.get("type").asText());
        assertEquals("amber", v1.at("/attributen/soort vondst").asText());
        assertEquals(0, new BigDecimal("9").compareTo(v1.at("/attributen/premie voor de vondst").decimalValue()),
                v1.toString());
        assertTrue(v1.at("/kenmerken/van waarde").asBoolean(), v1.toString());
        JsonNode j1 = instance(a, "J1");
        assertEquals("strandjutter", j1.get("type").asText());
        assertEquals(0, new BigDecimal("12.6").compareTo(j1.at("/attributen/totale premie").decimalValue()),
                j1.toString());
        assertEquals(1, j1.at("/attributen/aantal vondsten van waarde").asInt());
        assertTrue(j1.at("/kenmerken/komt in aanmerking voor premie").asBoolean(), j1.toString());
        assertTrue(a.get("uitleg").toString().contains("V1 is van waarde, omdat zijn soort (amber · barnsteen)"
                + " is gelijk aan amber · barnsteen"),
                a.get("uitleg").toString());
    }

    @Test
    void the_default_vocabulary_of_an_entrypoint_and_canonical_as_choice() {
        JsonNode v1 = instance(GOLDEN.process("premie-cgm", VocabularySource.json(VocabularySource.CASE1)), "V1");
        assertEquals("VONDST", v1.get("type").asText());
        assertEquals("BARNSTEEN", v1.at("/attributen/VONDST_SOORT").asText());
        assertTrue(v1.at("/kenmerken/WAARDEVOL").asBoolean(), v1.toString());

        ObjectNode canonical = GOLDEN.process("premie-cgm", VocabularySource.json(VocabularySource.CASE1),
                new RequestOptions(false, "canoniek", null));
        assertEquals(GOLDEN.process("premie", VocabularySource.json(VocabularySource.CASE1)), canonical,
                "canoniek gekozen is het antwoord van het entrypoint zonder standaard");
    }

    @Test
    void a_declaration_without_designation_carries_its_canonical_name_and_an_inconsistency_its_designation() {
        // 'toeslag' is ambiguous here unless the canonical reading is pinned
        ObjectNode a = AMBIGUOUS.process("uitkering", VocabularySource.json("""
            { "instanties": [ { "type": "Jutter", "id": "J1",
                "attributen": { "vondstbedrag": 2, "premiebedrag": 2, "basisbedrag": 1, "toeslag": 1, "houtsoort": "wrakhout" } } ] }"""),
                new RequestOptions(false, "cgm", "canoniek"));
        JsonNode j1 = instance(a, "J1");
        assertEquals("JUTTER", j1.get("type").asText());
        assertEquals(0, new BigDecimal("6").compareTo(j1.at("/attributen/UITKERING").decimalValue()), j1.toString());
        assertEquals("WRAKHOUT", j1.at("/attributen/houtsoort").asText(), "houtsoort heeft in cgm geen benaming");
        assertTrue(j1.at("/kenmerken/HOUTJUTTER").asBoolean(), j1.toString());
        assertEquals("C_BEDRAGEN", a.at("/inconsistenties/0").asText(), a.toString());
        assertEquals("C_BEDRAGEN", a.at("/inconsistentieDetails/0/regel").asText(), a.toString());
        assertEquals("J1", a.at("/inconsistentieDetails/0/onderwerp").asText(), a.toString());
    }

    @Test
    void a_document_as_source_carries_the_vocabularies_of_uchar_loader() throws Exception {
        DecisionCore core = DecisionCore.buildFromDocument(VocabularySource.descriptorJson(),
                java.nio.file.Files.readString(VocabularySource.GOLDEN.resolve("ir.json")), CoreConfig.strict(),
                getClass().getClassLoader(), null);
        ObjectNode a = core.process("premie", VocabularySource.json(VocabularySource.CASE1_HANDBOEK),
                new RequestOptions(false, "cgm", null));
        assertEquals("BARNSTEEN", instance(a, "V1").at("/attributen/VONDST_SOORT").asText(), a.toString());
        assertEquals(0, new BigDecimal("12.6").compareTo(instance(a, "J1").at("/attributen/VONDST_BEDRAG").decimalValue()),
                a.toString());
    }

    @Test
    void an_unknown_default_vocabulary_refuses_the_start() {
        String descriptor = VocabularySource.descriptorJson().replace("\"vocabulaire\": \"cgm\"", "\"vocabulaire\": \"wetboek\"");
        ServiceError f = assertThrows(ServiceError.class, () -> DecisionCore.assemble(descriptor,
                Map.of("bron.rgs", VocabularySource.source()), CoreConfig.strict(), getClass().getClassLoader()));
        assertEquals(1, f.violations().size(), f.getMessage());
        assertEquals(Violation.Kind.ONBEKEND_VOCABULAIRE, f.violations().get(0).kind());
        assertEquals("entrypoints[1].vocabulaire", f.violations().get(0).field());
    }

    @Test
    void a_vocabulary_with_the_name_canonical_refuses_the_start() {
        String source = """
            Vocabulaire canoniek
            Objecttype de Jutter (mv: Jutters) (canoniek: strandjutter)
                de naam Tekst;
                de groet Tekst;
            Regel groet
                geldig altijd
                    De groet van een Jutter moet gesteld worden op zijn naam.
            """;
        String descriptor = """
            { "dienst": {"naam":"gereserveerd","versie":"1"},
              "entrypoints": [ { "naam":"groet","pad":"groet", "scope": {"regelnamen": ["groet"]},
                "invoer": ["Jutter"], "uitvoer": ["Jutter.groet"] } ], "parameters": {} }""";
        ServiceError f = assertThrows(ServiceError.class, () -> DecisionCore.assemble(descriptor, Map.of("g.rgs", source),
                CoreConfig.strict(), getClass().getClassLoader()));
        assertEquals(Violation.Kind.MALFORMED, f.violations().get(0).kind(), f.getMessage());
        assertEquals("het model declareert een vocabulaire 'canoniek', en 'canoniek' kiest de canonieke namen; geef"
                + " het vocabulaire een andere naam", f.violations().get(0).message());
    }
}
