package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service over a {@code uchar-ir-v1} document instead of RegelSpraak sources, and the request forms it maps as
 * uchar's replay maps a case: a role written by its name, an enumeration value spelled otherwise than declared, an
 * inconsistency with its subject. The documents are goldens of uchar's corpus (the submodule).
 */
class DocumentSourceTest {

    private static final Path CORPUS = TokaSource.REPO.resolve("uchar/corpus/golden");

    private static String document(String golden) throws IOException {
        return Files.readString(CORPUS.resolve(golden).resolve("ir.json"));
    }

    private static String descriptor(String scope, String input, String output) {
        return "{ \"service\": {\"name\":\"document\",\"version\":\"1\"}, \"entrypoints\": [ { \"name\":\"e\","
                + "\"path\":\"e\", \"scope\": {\"ruleNames\": [" + scope + "]}, \"input\": [" + input + "],"
                + " \"output\": [" + output + "] } ], \"parameters\": {} }";
    }

    private static JsonNode request(String json) throws IOException {
        return DescriptorLoader.MAPPER.readTree(json);
    }

    private static JsonNode instance(ObjectNode answer, String id) {
        for (JsonNode i : answer.get("instances")) if (id.equals(i.get("id").asText())) return i;
        throw new AssertionError("geen instantie " + id + " in " + answer);
    }

    @Test
    void a_document_is_served_and_a_role_comes_as_uchar_replay_sets_it() throws IOException {
        // The Berging names its bergers under the written role 'bergers', to-many from the Berging, whose storage
        // is the far end's field: uchar's replay writes it through the sister role, and so does the service now.
        // Until then the service refused two targets on a role it read as to-one.
        DecisionCore core = DecisionCore.buildFromDocument(
                descriptor("\"verdeeld aandeel van een berging\"", "\"Berging\"", "\"Berging.verdeeld aandeel\""),
                document("jutterswet-aandelensom"), CoreConfig.strict(), getClass().getClassLoader(), null);
        ObjectNode answer = core.process("e", request("""
            { "instances": [
                { "type": "Berging", "id": "B1", "roles": { "bergers": ["G1", "G2"] } },
                { "type": "Berger", "id": "G1", "attributes": { "gewichtsaandeel": 45 } },
                { "type": "Berger", "id": "G2", "attributes": { "gewichtsaandeel": 30 } } ] }"""));
        assertEquals(0, new java.math.BigDecimal("75").compareTo(
                instance(answer, "B1").get("attributes").get("verdeeld aandeel").decimalValue()), answer.toString());
    }

    @Test
    void a_scope_that_does_not_carry_the_whole_document_refuses_the_start() {
        // a document without its source has no model to drop forms from, so it is served whole
        ServiceError f = assertThrows(ServiceError.class, () -> DecisionCore.buildFromDocument(
                descriptor("\"penninggerechtigd tenzij uitgesloten\"", "\"Jutter\"", "\"Jutter:penninggerechtigd\""),
                document("jutterswet-uitsluiting-rgs"), CoreConfig.withMarkers(), getClass().getClassLoader(), null));
        assertEquals(Violation.Kind.MALFORMED, f.violations().get(0).kind(), f.getMessage());
        assertTrue(f.getMessage().contains("heel gediend") && f.getMessage().contains("uitsluiting van het penningrecht"),
                f.getMessage());
    }

    @Test
    void a_document_that_uchar_loader_refuses_does_not_carry_uchar() {
        ServiceError f = assertThrows(ServiceError.class, () -> DecisionCore.buildFromDocument(
                descriptor("\"x\"", "", ""), document("jutterswet-kinderdeler"), CoreConfig.withMarkers(),
                getClass().getClassLoader(), null));
        assertEquals(1, f.violations().size(), f.getMessage());
        assertEquals(Violation.Kind.MARKER, f.violations().get(0).kind(), f.getMessage());
        assertTrue(f.getMessage().contains("loader:"), f.getMessage());
    }

    @Test
    void an_enumeration_value_is_compared_as_uchar_compares_declared_names() throws IOException {
        // "Beschadigd" names the declared "beschadigd" (uchar's Namen.norm); the request's spelling is kept, as
        // uchar's replay keeps a case's, and the rules read it alike
        DecisionCore core = DecisionCore.buildFromDocument(descriptor(
                "\"veilbaarheid naar staat\", \"afkeuring naar staat\", \"oordeel volgt de staat\","
                        + " \"stempel voor een veilbare vondst\"", "\"Vondst\"",
                "\"Vondst:veilbaar\", \"Vondst:afgekeurd\""),
                document("jutterswet-keurstaat"), CoreConfig.strict(), getClass().getClassLoader(), null);
        ObjectNode answer = core.process("e", request("""
            { "instances": [ { "type": "Vondst", "id": "V1", "attributes": { "staat": "Beschadigd" } } ] }"""));
        JsonNode characteristics = instance(answer, "V1").get("characteristics");
        assertTrue(characteristics.get("afgekeurd").asBoolean(), answer.toString());
        assertFalse(characteristics.get("veilbaar").asBoolean(), answer.toString());

        ServiceError f = assertThrows(ServiceError.class, () -> core.process("e", request("""
            { "instances": [ { "type": "Vondst", "id": "V1", "attributes": { "staat": "verdwenen" } } ] }""")));
        assertEquals(Violation.Kind.UNKNOWN_ENUM_VALUE, f.violations().get(0).kind(), f.getMessage());
    }

    @Test
    void an_inconsistency_names_its_subject() throws IOException {
        String source = """
            Objecttype de Vlucht (mv: Vluchten)
                de vertrekcode Tekst;
                de bestemmingscode Tekst;
            Regel geen rondvlucht
            geldig altijd
            De vertrekcode van een Vlucht moet ongelijk zijn aan de bestemmingscode van de Vlucht.
            """;
        DecisionCore core = DecisionCore.assemble(descriptor("\"geen rondvlucht\"", "\"Vlucht\"", ""),
                Map.of("vlucht.rgs", source), CoreConfig.strict(), getClass().getClassLoader());
        ObjectNode answer = core.process("e", request("""
            { "instances": [
                { "type": "Vlucht", "id": "V1", "attributes": { "vertrekcode": "AMS", "bestemmingscode": "BRU" } },
                { "type": "Vlucht", "id": "V2", "attributes": { "vertrekcode": "AMS", "bestemmingscode": "AMS" } } ] }"""));
        assertEquals(1, answer.get("inconsistencies").size(), answer.toString());
        JsonNode detail = answer.get("inconsistencyDetails").get(0);
        assertEquals("V2", detail.get("subject").asText(), answer.toString());
        assertEquals(answer.get("inconsistencies").get(0).asText(), detail.get("rule").asText());
    }
}
