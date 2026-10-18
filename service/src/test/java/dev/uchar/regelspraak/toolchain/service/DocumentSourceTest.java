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
class DocumentBronTest {

    private static final Path CORPUS = TokaBron.REPO.resolve("uchar/corpus/golden");

    private static String document(String golden) throws IOException {
        return Files.readString(CORPUS.resolve(golden).resolve("ir.json"));
    }

    private static String descriptor(String scope, String invoer, String uitvoer) {
        return "{ \"dienst\": {\"naam\":\"document\",\"versie\":\"1\"}, \"entrypoints\": [ { \"naam\":\"e\","
                + "\"pad\":\"e\", \"scope\": {\"regelnamen\": [" + scope + "]}, \"invoer\": [" + invoer + "],"
                + " \"uitvoer\": [" + uitvoer + "] } ], \"parameters\": {} }";
    }

    private static JsonNode verzoek(String json) throws IOException {
        return DescriptorLoader.MAPPER.readTree(json);
    }

    private static JsonNode instantie(ObjectNode antwoord, String id) {
        for (JsonNode i : antwoord.get("instanties")) if (id.equals(i.get("id").asText())) return i;
        throw new AssertionError("geen instantie " + id + " in " + antwoord);
    }

    @Test
    void een_document_wordt_gediend_en_een_rol_komt_zoals_uchars_replay_hem_zet() throws IOException {
        // The Berging names its bergers under the written role 'bergers', to-many from the Berging, whose storage
        // is the far end's field: uchar's replay writes it through the sister role, and so does the service now.
        // Until then the service refused two targets on a role it read as to-one.
        BeslisserKern kern = BeslisserKern.bouwVanDocument(
                descriptor("\"verdeeld aandeel van een berging\"", "\"Berging\"", "\"Berging.verdeeld aandeel\""),
                document("jutterswet-aandelensom"), KernConfig.strikt(), getClass().getClassLoader(), null);
        ObjectNode antwoord = kern.verwerk("e", verzoek("""
            { "instanties": [
                { "type": "Berging", "id": "B1", "rollen": { "bergers": ["G1", "G2"] } },
                { "type": "Berger", "id": "G1", "attributen": { "gewichtsaandeel": 45 } },
                { "type": "Berger", "id": "G2", "attributen": { "gewichtsaandeel": 30 } } ] }"""));
        assertEquals(0, new java.math.BigDecimal("75").compareTo(
                instantie(antwoord, "B1").get("attributen").get("verdeeld aandeel").decimalValue()), antwoord.toString());
    }

    @Test
    void een_scope_die_niet_het_hele_document_draagt_weigert_de_start() {
        // a document without its source has no model to drop forms from, so it is served whole
        DienstFout f = assertThrows(DienstFout.class, () -> BeslisserKern.bouwVanDocument(
                descriptor("\"penninggerechtigd tenzij uitgesloten\"", "\"Jutter\"", "\"Jutter:penninggerechtigd\""),
                document("jutterswet-uitsluiting-rgs"), KernConfig.metMarkers(), getClass().getClassLoader(), null));
        assertEquals(Schending.Soort.MALFORMED, f.schendingen().get(0).soort(), f.getMessage());
        assertTrue(f.getMessage().contains("heel gediend") && f.getMessage().contains("uitsluiting van het penningrecht"),
                f.getMessage());
    }

    @Test
    void een_document_dat_uchars_lader_weigert_draagt_uchar_niet() {
        DienstFout f = assertThrows(DienstFout.class, () -> BeslisserKern.bouwVanDocument(
                descriptor("\"x\"", "", ""), document("jutterswet-kinderdeler"), KernConfig.metMarkers(),
                getClass().getClassLoader(), null));
        assertEquals(1, f.schendingen().size(), f.getMessage());
        assertEquals(Schending.Soort.MARKER, f.schendingen().get(0).soort(), f.getMessage());
        assertTrue(f.getMessage().contains("loader:"), f.getMessage());
    }

    @Test
    void een_enumeratiewaarde_wordt_vergeleken_zoals_uchar_gedeclareerde_namen_vergelijkt() throws IOException {
        // "Beschadigd" names the declared "beschadigd" (uchar's Namen.norm); the request's spelling is kept, as
        // uchar's replay keeps a case's, and the rules read it alike
        BeslisserKern kern = BeslisserKern.bouwVanDocument(descriptor(
                "\"veilbaarheid naar staat\", \"afkeuring naar staat\", \"oordeel volgt de staat\","
                        + " \"stempel voor een veilbare vondst\"", "\"Vondst\"",
                "\"Vondst:veilbaar\", \"Vondst:afgekeurd\""),
                document("jutterswet-keurstaat"), KernConfig.strikt(), getClass().getClassLoader(), null);
        ObjectNode antwoord = kern.verwerk("e", verzoek("""
            { "instanties": [ { "type": "Vondst", "id": "V1", "attributen": { "staat": "Beschadigd" } } ] }"""));
        JsonNode kenmerken = instantie(antwoord, "V1").get("kenmerken");
        assertTrue(kenmerken.get("afgekeurd").asBoolean(), antwoord.toString());
        assertFalse(kenmerken.get("veilbaar").asBoolean(), antwoord.toString());

        DienstFout f = assertThrows(DienstFout.class, () -> kern.verwerk("e", verzoek("""
            { "instanties": [ { "type": "Vondst", "id": "V1", "attributen": { "staat": "verdwenen" } } ] }""")));
        assertEquals(Schending.Soort.ONBEKENDE_ENUM_WAARDE, f.schendingen().get(0).soort(), f.getMessage());
    }

    @Test
    void een_inconsistentie_noemt_haar_onderwerp() throws IOException {
        String bron = """
            Objecttype de Vlucht (mv: Vluchten)
                de vertrekcode Tekst;
                de bestemmingscode Tekst;
            Regel geen rondvlucht
            geldig altijd
            De vertrekcode van een Vlucht moet ongelijk zijn aan de bestemmingscode van de Vlucht.
            """;
        BeslisserKern kern = BeslisserKern.bouw(descriptor("\"geen rondvlucht\"", "\"Vlucht\"", ""),
                Map.of("vlucht.rgs", bron), KernConfig.strikt(), getClass().getClassLoader());
        ObjectNode antwoord = kern.verwerk("e", verzoek("""
            { "instanties": [
                { "type": "Vlucht", "id": "V1", "attributen": { "vertrekcode": "AMS", "bestemmingscode": "BRU" } },
                { "type": "Vlucht", "id": "V2", "attributen": { "vertrekcode": "AMS", "bestemmingscode": "AMS" } } ] }"""));
        assertEquals(1, antwoord.get("inconsistenties").size(), antwoord.toString());
        JsonNode detail = antwoord.get("inconsistentieDetails").get(0);
        assertEquals("V2", detail.get("onderwerp").asText(), antwoord.toString());
        assertEquals(antwoord.get("inconsistenties").get(0).asText(), detail.get("regel").asText());
    }
}
