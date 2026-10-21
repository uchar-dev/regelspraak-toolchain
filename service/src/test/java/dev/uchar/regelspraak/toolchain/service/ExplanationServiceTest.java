package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.toolchain.service.http.ServiceHttp;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code ?explanation=true} optional response field (fase: UITLEG-generator). Proves the service exposes the
 * {@link dev.uchar.regelspraak.toolchain.engine.Verbaliser} verhaal as an ADDITIVE, descriptor-independent field — present
 * only when asked, and a NO-OP for existing callers (no {@code explanation} key on the plain path, so no contract
 * breaks). Also pins the {@link ServiceHttp#requiresExplanation} query-flag parsing the HTTP adapter uses.
 */
class ExplanationServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static DecisionCore CORE;

    @BeforeAll
    static void assemble() {
        CORE = DecisionCore.assemble(TokaSource.descriptorJson(), TokaSource.sourcePerFile(),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
    }

    /** A kenmerktoekenning entrypoint (minderjarig) with ?explanation=true carries an "explanation" array that VERBALISES
     *  the kenmerk with its evaluated operanden — one NL sentence per Natuurlijk persoon in the request. */
    @Test
    void uitleg_true_adds_a_story() {
        String request = """
            { "instances": [
                { "type": "Vlucht", "id": "V1", "attributes": { "vluchtdatum": "2024-06-15" } },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributes": { "geboortedatum": "2015-06-15" },
                  "roles": { "reis": "V1" } } ] }""";
        ObjectNode answer = CORE.process("kenmerktoekenning-persoon-minderjarig", parse(request), true);
        assertTrue(answer.has("explanation") && answer.get("explanation").isArray(),
                "verwacht een uitleg-array: " + answer);
        assertFalse(answer.get("explanation").isEmpty(), "verwacht ten minste één uitlegzin: " + answer);
        boolean namesCharacteristic = false;
        for (JsonNode sentence : answer.get("explanation"))
            if (sentence.asText().contains("minderjarig")) namesCharacteristic = true;
        assertTrue(namesCharacteristic, "de uitleg hoort het kenmerk 'minderjarig' te noemen: " + answer);
    }

    /** The golden sentence of the minderjarig decision, pinned over the scenario route until the move to uchar,
     *  whose replay returns no fired facts to verbalise. Every operand has the value and the unit the engine read
     *  (card explanation-from-readings): the parameter (18 jr) and the tijdsduur (9 jr), which until that card stood
     *  as "(onbekend)", because the verbalisator, which then computed itself, did not compute a tijdsduur. */
    @Test
    void golden_explanation_sentence_minderjarig() {
        String request = """
            { "parameters": { "volwassenleeftijd": 18 },
              "instances": [
                { "type": "Vlucht", "id": "V1", "attributes": { "vluchtdatum": "2024-06-15" } },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributes": { "geboortedatum": "2015-06-15" },
                  "roles": { "reis": "V1" } } ] }""";
        ObjectNode answer = CORE.process("kenmerktoekenning-persoon-minderjarig", parse(request), true);
        assertTrue(contains(answer.get("explanation"),
                "P1 is minderjarig, omdat de tijdsduur van zijn geboortedatum tot de vluchtdatum van zijn reis"
                        + " in hele jaren (9 jr) is kleiner dan de volwassenleeftijd (18 jr)"),
                "de gouden uitlegzin: " + answer.get("explanation"));
    }

    private static boolean contains(JsonNode sentences, String sentence) {
        for (JsonNode z : sentences) if (z.asText().equals(sentence)) return true;
        return false;
    }

    /** The plain path (no flag, the existing 2-arg contract) is UNCHANGED: no "explanation" key — every current
     *  caller's response tree is byte-identical to before the feature. */
    @Test
    void without_flag_no_uitleg_field() {
        String request = """
            { "instances": [
                { "type": "Vlucht", "id": "V1", "attributes": { "vluchtdatum": "2024-06-15" } },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributes": { "geboortedatum": "2015-06-15" },
                  "roles": { "reis": "V1" } } ] }""";
        ObjectNode answer = CORE.process("kenmerktoekenning-persoon-minderjarig", parse(request));
        assertFalse(answer.has("explanation"), "de plain-path response mag GEEN uitleg-veld dragen: " + answer);
    }

    /** The HTTP adapter's query-flag parsing: explanation=true / a bare uitleg opt IN; explanation=false / absent opt OUT. */
    @Test
    void requires_uitleg_reads_the_query_correct() {
        assertTrue(ServiceHttp.requiresExplanation("explanation=true"));
        assertTrue(ServiceHttp.requiresExplanation("explanation=TRUE"));
        assertTrue(ServiceHttp.requiresExplanation("explanation"));
        assertTrue(ServiceHttp.requiresExplanation("foo=bar&explanation=true"));
        assertFalse(ServiceHttp.requiresExplanation("explanation=false"));
        assertFalse(ServiceHttp.requiresExplanation("explanation=nee"));
        assertFalse(ServiceHttp.requiresExplanation(""));
        assertFalse(ServiceHttp.requiresExplanation(null));
        assertFalse(ServiceHttp.requiresExplanation("andere=waarde"));
    }

    private static JsonNode parse(String json) {
        try { return MAPPER.readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
