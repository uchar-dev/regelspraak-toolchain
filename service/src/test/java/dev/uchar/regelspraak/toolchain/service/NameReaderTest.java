package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request reader of the service ({@link NameReader} through {@link DecisionCore#process}): every name canonical or
 * a designation in any vocabulary, mixed within one request; a name that reads as two declarations refused with both
 * readings; the input vocabulary pinned as the way out; an unknown vocabulary refused with the declared ones. The
 * golden {@code jutterswet-vocabulaires} gives the expectations of its case1 (V1 premie 9.00, J1 vondstbedrag 12.60,
 * J2 1.20); the own material {@link VocabularySource#AMBIGUOUS} gives the ambiguities.
 */
class NameReaderTest {

    private static DecisionCore GOLDEN;
    private static DecisionCore AMBIGUOUS;

    @BeforeAll
    static void assemble() {
        GOLDEN = VocabularySource.core();
        AMBIGUOUS = VocabularySource.ambiguous();
    }

    private static JsonNode instance(ObjectNode answer, String id) {
        for (JsonNode i : answer.get("instances")) if (id.equals(i.get("id").asText())) return i;
        throw new AssertionError("geen instantie " + id + " in " + answer);
    }

    private static void givesCase1(ObjectNode answer) {
        assertEquals(0, new BigDecimal("9.00").compareTo(instance(answer, "V1").at("/attributes/premie").decimalValue()),
                answer.toString());
        assertEquals(0, new BigDecimal("12.60").compareTo(
                instance(answer, "J1").at("/attributes/vondstbedrag").decimalValue()), answer.toString());
        assertEquals(0, new BigDecimal("1.20").compareTo(
                instance(answer, "J2").at("/attributes/vondstbedrag").decimalValue()), answer.toString());
        assertTrue(instance(answer, "V1").at("/characteristics/waardevol").asBoolean(), answer.toString());
        assertTrue(instance(answer, "J1").at("/characteristics/premiewaardig").asBoolean(), answer.toString());
    }

    private static Violation only(ServiceError f) {
        assertEquals(1, f.violations().size(), f.getMessage());
        return f.violations().get(0);
    }

    @Test
    void every_name_in_a_vocabulary_is_read() {
        givesCase1(GOLDEN.process("premie", VocabularySource.json(VocabularySource.CASE1_HANDBOEK)));
    }

    @Test
    void a_mixed_spelling_with_a_relation_is_read() {
        givesCase1(GOLDEN.process("premie", VocabularySource.json(VocabularySource.CASE1_MIXED)));
    }

    @Test
    void a_canonical_request_is_read_as_always() {
        givesCase1(GOLDEN.process("premie", VocabularySource.json(VocabularySource.CASE1)));
    }

    @Test
    void a_name_that_names_two_declarations_in_two_vocabularies_is_ambiguous() {
        ServiceError f = assertThrows(ServiceError.class, () -> AMBIGUOUS.process("uitkering", VocabularySource.json("""
            { "instances": [ { "type": "Jutter", "id": "J1", "attributes": { "bedrag": 10 } } ] }""")));
        Violation s = only(f);
        assertEquals(Violation.Kind.AMBIGUOUS_NAME, s.kind());
        assertEquals("instances[0].attributes.bedrag", s.field());
        assertEquals("de naam 'bedrag' is dubbelzinnig: in het vocabulaire 'handboek' het attribuut 'vondstbedrag' van"
                + " 'Jutter', in het vocabulaire 'cgm' het attribuut 'premiebedrag' van 'Jutter'; zet het vocabulaire"
                + " erbij met de queryparameter invoervocabulaire (handboek of cgm)", s.message());
    }

    @Test
    void a_designation_opposite_a_canonical_name_is_ambiguous() {
        ServiceError f = assertThrows(ServiceError.class, () -> AMBIGUOUS.process("uitkering", VocabularySource.json("""
            { "instances": [ { "type": "Jutter", "id": "J1", "attributes": { "toeslag": 10, "houtsoort": "hout" } } ] }""")));
        assertEquals(2, f.violations().size(), f.getMessage());
        // cgm gives the toeslag no designation, so there its effective name is the canonical one
        assertEquals("de naam 'toeslag' is dubbelzinnig: als canonieke naam en in het vocabulaire 'cgm' het attribuut"
                + " 'toeslag' van 'Jutter', in het vocabulaire 'handboek' het attribuut 'basisbedrag' van 'Jutter'; zet"
                + " het vocabulaire erbij met de queryparameter invoervocabulaire (canonical, cgm of handboek)",
                f.violations().get(0).message());
        assertEquals(Violation.Kind.AMBIGUOUS_NAME, f.violations().get(1).kind());
        assertEquals("instances[0].attributes.houtsoort", f.violations().get(1).field());
        assertEquals("de naam 'hout' is dubbelzinnig: als canonieke naam en in het vocabulaire 'cgm' de waarde 'hout'"
                + " van domein 'Houtsoort', in het vocabulaire 'handboek' de waarde 'wrakhout' van domein 'Houtsoort';"
                + " zet het vocabulaire erbij met de queryparameter invoervocabulaire (canonical, cgm of handboek)",
                f.violations().get(1).message());
    }

    @Test
    void the_fixed_invoervocabulaire_decides() {
        String request = """
            { "instances": [ { "type": "Jutter", "id": "J1",
                "attributes": { "bedrag": 10, "premiebedrag": 2.5, "toeslag": 1, "bijslag": 0.25, "houtsoort": "hout" } } ] }""";
        ObjectNode handboek = AMBIGUOUS.process("uitkering", VocabularySource.json(request),
                new RequestOptions(false, null, "handboek"));
        JsonNode j1 = instance(handboek, "J1");
        assertEquals(0, new BigDecimal("13.75").compareTo(j1.at("/attributes/uitkering").decimalValue()), j1.toString());
        assertEquals("wrakhout", j1.at("/attributes/houtsoort").asText(), "'hout' is in het handboek de wrakhout");
        assertTrue(j1.at("/characteristics/houtjutter").asBoolean(), j1.toString());

        ObjectNode canonical = AMBIGUOUS.process("uitkering", VocabularySource.json("""
            { "instances": [ { "type": "Jutter", "id": "J1",
                "attributes": { "vondstbedrag": 1, "premiebedrag": 2, "basisbedrag": 3, "toeslag": 4, "houtsoort": "hout" } } ] }"""),
                new RequestOptions(false, null, "canonical"));
        assertEquals(0, new BigDecimal("10").compareTo(instance(canonical, "J1").at("/attributes/uitkering").decimalValue()));
        assertEquals("hout", instance(canonical, "J1").at("/attributes/houtsoort").asText());
    }

    @Test
    void under_a_fixed_vocabulary_another_spelling_is_unknown() {
        ServiceError f = assertThrows(ServiceError.class, () -> GOLDEN.process("premie",
                VocabularySource.json(VocabularySource.CASE1), new RequestOptions(false, null, "handboek")));
        List<String> messages = f.violations().stream().map(Violation::message).toList();
        assertTrue(messages.contains("onbekende parameter 'premietarief' (invoervocabulaire 'handboek')"), messages.toString());
        assertTrue(messages.contains("onbekend objecttype 'Jutter' (invoervocabulaire 'handboek')"), messages.toString());

        ServiceError value = assertThrows(ServiceError.class, () -> GOLDEN.process("premie", VocabularySource.json("""
            { "instances": [ { "type": "aangespoeld voorwerp", "id": "V1", "attributes": { "soort vondst": "barnsteen" } } ] }"""),
                new RequestOptions(false, null, "handboek")));
        Violation s = only(value);
        assertEquals(Violation.Kind.UNKNOWN_ENUM_VALUE, s.kind());
        assertEquals("'barnsteen' is geen waarde van domein 'Vondstsoort' (toegestaan in het invoervocabulaire"
                + " 'handboek': [hout van een wrak, amber, bericht in een fles])", s.message());
    }

    @Test
    void two_spellings_of_a_declaration_in_an_object_are_two_values() {
        ServiceError f = assertThrows(ServiceError.class, () -> GOLDEN.process("premie", VocabularySource.json("""
            { "instances": [ { "type": "Vondst", "id": "V1", "attributes": { "gewicht": 1, "GEWICHT": 2 } } ] }""")));
        Violation s = only(f);
        assertEquals(Violation.Kind.MALFORMED, s.kind());
        assertEquals("instances[0].attributes.GEWICHT", s.field());
        assertEquals("het attribuut 'gewicht' van 'Vondst' staat twee keer in deze instantie: als 'gewicht' en als"
                + " 'GEWICHT'", s.message());
    }

    @Test
    void an_unknown_vocabulary_is_a_refusal_of_its_own() {
        ServiceError f = assertThrows(ServiceError.class, () -> GOLDEN.process("premie",
                VocabularySource.json(VocabularySource.CASE1), new RequestOptions(false, "wetboek", "Handboek2")));
        assertEquals(2, f.violations().size(), f.getMessage());
        assertEquals(Violation.Kind.UNKNOWN_VOCABULARY, f.violations().get(0).kind());
        assertEquals("vocabulary", f.violations().get(0).field());
        assertEquals("het vocabulaire 'wetboek' is niet gedeclareerd; het model declareert 'handboek' en 'cgm', en"
                + " 'canonical' kiest de canonieke namen", f.violations().get(0).message());
        assertEquals("inputVocabulary", f.violations().get(1).field());
    }

    @Test
    void an_unknown_name_keeps_the_existing_refusal() {
        ServiceError f = assertThrows(ServiceError.class, () -> GOLDEN.process("premie", VocabularySource.json("""
            { "instances": [ { "type": "strandjutter", "id": "J1", "attributes": { "schoenmaat": 42 } } ] }""")));
        Violation s = only(f);
        assertEquals(Violation.Kind.UNKNOWN_FIELD, s.kind());
        assertEquals("onbekend attribuut 'schoenmaat' op objecttype 'Jutter'", s.message());
    }
}
