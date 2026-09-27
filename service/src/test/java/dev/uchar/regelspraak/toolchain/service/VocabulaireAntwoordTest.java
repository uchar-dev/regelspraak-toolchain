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
 * The answer in the vocabulary a request chooses ({@link AntwoordBouwer} with a
 * {@link dev.uchar.regelspraak.toolchain.engine.Naamgeving}): the object type, the attributes and characteristics, an
 * enumeration value, the rule of an inconsistency and the explanation in its names; the descriptor's default per
 * entry point; the fallback on the canonical name of a declaration without a designation; the canonical answer as it
 * always was; and the start refused on a default vocabulary the model does not declare, or on a vocabulary named
 * {@code canoniek}.
 */
class VocabulaireAntwoordTest {

    private static BeslisserKern GOLDEN;
    private static BeslisserKern DUBBELZINNIG;

    @BeforeAll
    static void bouw() {
        GOLDEN = VocabulaireBron.kern();
        DUBBELZINNIG = VocabulaireBron.dubbelzinnig();
    }

    private static JsonNode instantie(ObjectNode antwoord, String id) {
        for (JsonNode i : antwoord.get("instanties")) if (id.equals(i.get("id").asText())) return i;
        throw new AssertionError("geen instantie " + id + " in " + antwoord);
    }

    @Test
    void het_antwoord_in_het_handboek() {
        ObjectNode a = GOLDEN.verwerk("premie", VocabulaireBron.json(VocabulaireBron.CASE1),
                new VerzoekOpties(true, "handboek", null));
        JsonNode v1 = instantie(a, "V1");
        assertEquals("aangespoeld voorwerp", v1.get("type").asText());
        assertEquals("amber", v1.at("/attributen/soort vondst").asText());
        assertEquals(0, new BigDecimal("9").compareTo(v1.at("/attributen/premie voor de vondst").decimalValue()),
                v1.toString());
        assertTrue(v1.at("/kenmerken/van waarde").asBoolean(), v1.toString());
        JsonNode j1 = instantie(a, "J1");
        assertEquals("strandjutter", j1.get("type").asText());
        assertEquals(0, new BigDecimal("12.6").compareTo(j1.at("/attributen/totale premie").decimalValue()),
                j1.toString());
        assertEquals(1, j1.at("/attributen/aantal vondsten van waarde").asInt());
        assertTrue(j1.at("/kenmerken/komt in aanmerking voor premie").asBoolean(), j1.toString());
        assertTrue(a.get("uitleg").toString().contains("V1 is van waarde, omdat zijn soort (amber) is gelijk aan amber"),
                a.get("uitleg").toString());
    }

    @Test
    void het_standaardvocabulaire_van_een_entrypoint_en_canoniek_als_keuze() {
        JsonNode v1 = instantie(GOLDEN.verwerk("premie-cgm", VocabulaireBron.json(VocabulaireBron.CASE1)), "V1");
        assertEquals("VONDST", v1.get("type").asText());
        assertEquals("BARNSTEEN", v1.at("/attributen/VONDST_SOORT").asText());
        assertTrue(v1.at("/kenmerken/WAARDEVOL").asBoolean(), v1.toString());

        ObjectNode canoniek = GOLDEN.verwerk("premie-cgm", VocabulaireBron.json(VocabulaireBron.CASE1),
                new VerzoekOpties(false, "canoniek", null));
        assertEquals(GOLDEN.verwerk("premie", VocabulaireBron.json(VocabulaireBron.CASE1)), canoniek,
                "canoniek gekozen is het antwoord van het entrypoint zonder standaard");
    }

    @Test
    void een_declaratie_zonder_benaming_draagt_haar_canonieke_naam_en_een_inconsistentie_haar_benaming() {
        // 'toeslag' is ambiguous here unless the canonical reading is pinned
        ObjectNode a = DUBBELZINNIG.verwerk("uitkering", VocabulaireBron.json("""
            { "instanties": [ { "type": "Jutter", "id": "J1",
                "attributen": { "vondstbedrag": 2, "premiebedrag": 2, "basisbedrag": 1, "toeslag": 1, "houtsoort": "wrakhout" } } ] }"""),
                new VerzoekOpties(false, "cgm", "canoniek"));
        JsonNode j1 = instantie(a, "J1");
        assertEquals("JUTTER", j1.get("type").asText());
        assertEquals(0, new BigDecimal("6").compareTo(j1.at("/attributen/UITKERING").decimalValue()), j1.toString());
        assertEquals("WRAKHOUT", j1.at("/attributen/houtsoort").asText(), "houtsoort heeft in cgm geen benaming");
        assertTrue(j1.at("/kenmerken/HOUTJUTTER").asBoolean(), j1.toString());
        assertEquals("C_BEDRAGEN", a.at("/inconsistenties/0").asText(), a.toString());
        assertEquals("C_BEDRAGEN", a.at("/inconsistentieDetails/0/regel").asText(), a.toString());
        assertEquals("J1", a.at("/inconsistentieDetails/0/onderwerp").asText(), a.toString());
    }

    @Test
    void een_onbekend_standaardvocabulaire_weigert_de_start() {
        String descriptor = VocabulaireBron.descriptorJson().replace("\"vocabulaire\": \"cgm\"", "\"vocabulaire\": \"wetboek\"");
        DienstFout f = assertThrows(DienstFout.class, () -> BeslisserKern.bouw(descriptor,
                Map.of("bron.rgs", VocabulaireBron.bron()), KernConfig.strikt(), getClass().getClassLoader()));
        assertEquals(1, f.schendingen().size(), f.getMessage());
        assertEquals(Schending.Soort.ONBEKEND_VOCABULAIRE, f.schendingen().get(0).soort());
        assertEquals("entrypoints[1].vocabulaire", f.schendingen().get(0).veld());
    }

    @Test
    void een_vocabulaire_met_de_naam_canoniek_weigert_de_start() {
        String bron = """
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
        DienstFout f = assertThrows(DienstFout.class, () -> BeslisserKern.bouw(descriptor, Map.of("g.rgs", bron),
                KernConfig.strikt(), getClass().getClassLoader()));
        assertEquals(Schending.Soort.MALFORMED, f.schendingen().get(0).soort(), f.getMessage());
        assertEquals("het model declareert een vocabulaire 'canoniek', en 'canoniek' kiest de canonieke namen; geef"
                + " het vocabulaire een andere naam", f.schendingen().get(0).bericht());
    }
}
