package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.toolchain.service.http.DienstHttp;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code ?uitleg=true} optional response field (fase: UITLEG-generator). Proves the service exposes the
 * {@link dev.uchar.regelspraak.toolchain.engine.Verbalisator} verhaal as an ADDITIVE, descriptor-independent field — present
 * only when asked, and a NO-OP for existing callers (no {@code uitleg} key on the plain path, so no contract
 * breaks). Also pins the {@link DienstHttp#vraagtUitleg} query-flag parsing the HTTP adapter uses.
 */
class UitlegDienstTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static BeslisserKern KERN;

    @BeforeAll
    static void bouw() {
        KERN = BeslisserKern.bouw(TokaBron.descriptorJson(), TokaBron.bronPerBestand(),
                KernConfig.strikt(), Thread.currentThread().getContextClassLoader());
    }

    /** A kenmerktoekenning entrypoint (minderjarig) with ?uitleg=true carries an "uitleg" array that VERBALISES
     *  the kenmerk with its evaluated operanden — one NL sentence per Natuurlijk persoon in the request. */
    @Test
    void uitleg_true_voegt_een_verhaal_toe() {
        String verzoek = """
            { "instanties": [
                { "type": "Vlucht", "id": "V1", "attributen": { "vluchtdatum": "2024-06-15" } },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributen": { "geboortedatum": "2015-06-15" },
                  "rollen": { "reis": "V1" } } ] }""";
        ObjectNode antwoord = KERN.verwerk("kenmerktoekenning-persoon-minderjarig", parse(verzoek), true);
        assertTrue(antwoord.has("uitleg") && antwoord.get("uitleg").isArray(),
                "verwacht een uitleg-array: " + antwoord);
        assertFalse(antwoord.get("uitleg").isEmpty(), "verwacht ten minste één uitlegzin: " + antwoord);
        boolean noemtKenmerk = false;
        for (JsonNode zin : antwoord.get("uitleg"))
            if (zin.asText().contains("minderjarig")) noemtKenmerk = true;
        assertTrue(noemtKenmerk, "de uitleg hoort het kenmerk 'minderjarig' te noemen: " + antwoord);
    }

    /** The plain path (no flag, the existing 2-arg contract) is UNCHANGED: no "uitleg" key — every current
     *  caller's response tree is byte-identical to before the feature. */
    @Test
    void zonder_vlag_geen_uitleg_veld() {
        String verzoek = """
            { "instanties": [
                { "type": "Vlucht", "id": "V1", "attributen": { "vluchtdatum": "2024-06-15" } },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributen": { "geboortedatum": "2015-06-15" },
                  "rollen": { "reis": "V1" } } ] }""";
        ObjectNode antwoord = KERN.verwerk("kenmerktoekenning-persoon-minderjarig", parse(verzoek));
        assertFalse(antwoord.has("uitleg"), "de plain-path response mag GEEN uitleg-veld dragen: " + antwoord);
    }

    /** The HTTP adapter's query-flag parsing: uitleg=true / a bare uitleg opt IN; uitleg=false / absent opt OUT. */
    @Test
    void vraagt_uitleg_leest_de_query_correct() {
        assertTrue(DienstHttp.vraagtUitleg("uitleg=true"));
        assertTrue(DienstHttp.vraagtUitleg("uitleg=TRUE"));
        assertTrue(DienstHttp.vraagtUitleg("uitleg"));
        assertTrue(DienstHttp.vraagtUitleg("foo=bar&uitleg=true"));
        assertFalse(DienstHttp.vraagtUitleg("uitleg=false"));
        assertFalse(DienstHttp.vraagtUitleg("uitleg=nee"));
        assertFalse(DienstHttp.vraagtUitleg(""));
        assertFalse(DienstHttp.vraagtUitleg(null));
        assertFalse(DienstHttp.vraagtUitleg("andere=waarde"));
    }

    private static JsonNode parse(String json) {
        try { return MAPPER.readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
