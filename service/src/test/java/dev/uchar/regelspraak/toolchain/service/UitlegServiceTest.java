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
 * The {@code ?uitleg=true} optional response field (fase: UITLEG-generator). Proves the service exposes the
 * {@link dev.uchar.regelspraak.toolchain.engine.Verbaliser} verhaal as an ADDITIVE, descriptor-independent field — present
 * only when asked, and a NO-OP for existing callers (no {@code uitleg} key on the plain path, so no contract
 * breaks). Also pins the {@link ServiceHttp#requiresUitleg} query-flag parsing the HTTP adapter uses.
 */
class UitlegServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static DecisionCore CORE;

    @BeforeAll
    static void assemble() {
        CORE = DecisionCore.assemble(TokaSource.descriptorJson(), TokaSource.sourcePerFile(),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
    }

    /** A kenmerktoekenning entrypoint (minderjarig) with ?uitleg=true carries an "uitleg" array that VERBALISES
     *  the kenmerk with its evaluated operanden — one NL sentence per Natuurlijk persoon in the request. */
    @Test
    void uitleg_true_adds_a_story() {
        String request = """
            { "instanties": [
                { "type": "Vlucht", "id": "V1", "attributen": { "vluchtdatum": "2024-06-15" } },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributen": { "geboortedatum": "2015-06-15" },
                  "rollen": { "reis": "V1" } } ] }""";
        ObjectNode answer = CORE.process("kenmerktoekenning-persoon-minderjarig", parse(request), true);
        assertTrue(answer.has("uitleg") && answer.get("uitleg").isArray(),
                "verwacht een uitleg-array: " + answer);
        assertFalse(answer.get("uitleg").isEmpty(), "verwacht ten minste één uitlegzin: " + answer);
        boolean namesCharacteristic = false;
        for (JsonNode sentence : answer.get("uitleg"))
            if (sentence.asText().contains("minderjarig")) namesCharacteristic = true;
        assertTrue(namesCharacteristic, "de uitleg hoort het kenmerk 'minderjarig' te noemen: " + answer);
    }

    /** The golden sentence of the minderjarig decision, pinned over the scenario route until the move to uchar,
     *  whose replay returns no fired facts to verbalise. A parameter operand is read from the globals and rendered
     *  with its unit (18 jr); the tijdsduur the verbalisator does not recompute is honestly "(onbekend)", never
     *  guessed. */
    @Test
    void golden_explanation_sentence_minderjarig() {
        String request = """
            { "parameters": { "volwassenleeftijd": 18 },
              "instanties": [
                { "type": "Vlucht", "id": "V1", "attributen": { "vluchtdatum": "2024-06-15" } },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributen": { "geboortedatum": "2015-06-15" },
                  "rollen": { "reis": "V1" } } ] }""";
        ObjectNode answer = CORE.process("kenmerktoekenning-persoon-minderjarig", parse(request), true);
        assertTrue(contains(answer.get("uitleg"),
                "P1 is minderjarig, omdat de tijdsduur van zijn geboortedatum tot de vluchtdatum van zijn reis"
                        + " in hele jaren (onbekend) is kleiner dan de volwassenleeftijd (18 jr)"),
                "de gouden uitlegzin: " + answer.get("uitleg"));
    }

    private static boolean contains(JsonNode sentences, String sentence) {
        for (JsonNode z : sentences) if (z.asText().equals(sentence)) return true;
        return false;
    }

    /** The plain path (no flag, the existing 2-arg contract) is UNCHANGED: no "uitleg" key — every current
     *  caller's response tree is byte-identical to before the feature. */
    @Test
    void without_flag_no_uitleg_field() {
        String request = """
            { "instanties": [
                { "type": "Vlucht", "id": "V1", "attributen": { "vluchtdatum": "2024-06-15" } },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributen": { "geboortedatum": "2015-06-15" },
                  "rollen": { "reis": "V1" } } ] }""";
        ObjectNode answer = CORE.process("kenmerktoekenning-persoon-minderjarig", parse(request));
        assertFalse(answer.has("uitleg"), "de plain-path response mag GEEN uitleg-veld dragen: " + answer);
    }

    /** The HTTP adapter's query-flag parsing: uitleg=true / a bare uitleg opt IN; uitleg=false / absent opt OUT. */
    @Test
    void requires_uitleg_reads_the_query_correct() {
        assertTrue(ServiceHttp.requiresUitleg("uitleg=true"));
        assertTrue(ServiceHttp.requiresUitleg("uitleg=TRUE"));
        assertTrue(ServiceHttp.requiresUitleg("uitleg"));
        assertTrue(ServiceHttp.requiresUitleg("foo=bar&uitleg=true"));
        assertFalse(ServiceHttp.requiresUitleg("uitleg=false"));
        assertFalse(ServiceHttp.requiresUitleg("uitleg=nee"));
        assertFalse(ServiceHttp.requiresUitleg(""));
        assertFalse(ServiceHttp.requiresUitleg(null));
        assertFalse(ServiceHttp.requiresUitleg("andere=waarde"));
    }

    private static JsonNode parse(String json) {
        try { return MAPPER.readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
