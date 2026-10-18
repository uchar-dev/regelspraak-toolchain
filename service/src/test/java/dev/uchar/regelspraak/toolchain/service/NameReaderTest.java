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
 * The request reader of the service ({@link NaamLezer} through {@link BeslisserKern#verwerk}): every name canonical or
 * a designation in any vocabulary, mixed within one request; a name that reads as two declarations refused with both
 * readings; the input vocabulary pinned as the way out; an unknown vocabulary refused with the declared ones. The
 * golden {@code jutterswet-vocabulaires} gives the expectations of its case1 (V1 premie 9.00, J1 vondstbedrag 12.60,
 * J2 1.20); the own material {@link VocabulaireBron#DUBBELZINNIG} gives the ambiguities.
 */
class NaamLezerTest {

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

    private static void geeftCase1(ObjectNode antwoord) {
        assertEquals(0, new BigDecimal("9.00").compareTo(instantie(antwoord, "V1").at("/attributen/premie").decimalValue()),
                antwoord.toString());
        assertEquals(0, new BigDecimal("12.60").compareTo(
                instantie(antwoord, "J1").at("/attributen/vondstbedrag").decimalValue()), antwoord.toString());
        assertEquals(0, new BigDecimal("1.20").compareTo(
                instantie(antwoord, "J2").at("/attributen/vondstbedrag").decimalValue()), antwoord.toString());
        assertTrue(instantie(antwoord, "V1").at("/kenmerken/waardevol").asBoolean(), antwoord.toString());
        assertTrue(instantie(antwoord, "J1").at("/kenmerken/premiewaardig").asBoolean(), antwoord.toString());
    }

    private static Schending enige(DienstFout f) {
        assertEquals(1, f.schendingen().size(), f.getMessage());
        return f.schendingen().get(0);
    }

    @Test
    void elke_naam_in_een_vocabulaire_wordt_gelezen() {
        geeftCase1(GOLDEN.verwerk("premie", VocabulaireBron.json(VocabulaireBron.CASE1_HANDBOEK)));
    }

    @Test
    void een_gemengde_spelling_met_een_relatie_wordt_gelezen() {
        geeftCase1(GOLDEN.verwerk("premie", VocabulaireBron.json(VocabulaireBron.CASE1_GEMENGD)));
    }

    @Test
    void een_canoniek_verzoek_wordt_gelezen_als_altijd() {
        geeftCase1(GOLDEN.verwerk("premie", VocabulaireBron.json(VocabulaireBron.CASE1)));
    }

    @Test
    void een_naam_die_in_twee_vocabulaires_twee_declaraties_noemt_is_dubbelzinnig() {
        DienstFout f = assertThrows(DienstFout.class, () -> DUBBELZINNIG.verwerk("uitkering", VocabulaireBron.json("""
            { "instanties": [ { "type": "Jutter", "id": "J1", "attributen": { "bedrag": 10 } } ] }""")));
        Schending s = enige(f);
        assertEquals(Schending.Soort.DUBBELZINNIGE_NAAM, s.soort());
        assertEquals("instanties[0].attributen.bedrag", s.veld());
        assertEquals("de naam 'bedrag' is dubbelzinnig: in het vocabulaire 'handboek' het attribuut 'vondstbedrag' van"
                + " 'Jutter', in het vocabulaire 'cgm' het attribuut 'premiebedrag' van 'Jutter'; zet het vocabulaire"
                + " erbij met de queryparameter invoervocabulaire (handboek of cgm)", s.bericht());
    }

    @Test
    void een_benaming_tegenover_een_canonieke_naam_is_dubbelzinnig() {
        DienstFout f = assertThrows(DienstFout.class, () -> DUBBELZINNIG.verwerk("uitkering", VocabulaireBron.json("""
            { "instanties": [ { "type": "Jutter", "id": "J1", "attributen": { "toeslag": 10, "houtsoort": "hout" } } ] }""")));
        assertEquals(2, f.schendingen().size(), f.getMessage());
        // cgm gives the toeslag no designation, so there its effective name is the canonical one
        assertEquals("de naam 'toeslag' is dubbelzinnig: als canonieke naam en in het vocabulaire 'cgm' het attribuut"
                + " 'toeslag' van 'Jutter', in het vocabulaire 'handboek' het attribuut 'basisbedrag' van 'Jutter'; zet"
                + " het vocabulaire erbij met de queryparameter invoervocabulaire (canoniek, cgm of handboek)",
                f.schendingen().get(0).bericht());
        assertEquals(Schending.Soort.DUBBELZINNIGE_NAAM, f.schendingen().get(1).soort());
        assertEquals("instanties[0].attributen.houtsoort", f.schendingen().get(1).veld());
        assertEquals("de naam 'hout' is dubbelzinnig: als canonieke naam en in het vocabulaire 'cgm' de waarde 'hout'"
                + " van domein 'Houtsoort', in het vocabulaire 'handboek' de waarde 'wrakhout' van domein 'Houtsoort';"
                + " zet het vocabulaire erbij met de queryparameter invoervocabulaire (canoniek, cgm of handboek)",
                f.schendingen().get(1).bericht());
    }

    @Test
    void het_vastgezette_invoervocabulaire_beslist() {
        String verzoek = """
            { "instanties": [ { "type": "Jutter", "id": "J1",
                "attributen": { "bedrag": 10, "premiebedrag": 2.5, "toeslag": 1, "bijslag": 0.25, "houtsoort": "hout" } } ] }""";
        ObjectNode handboek = DUBBELZINNIG.verwerk("uitkering", VocabulaireBron.json(verzoek),
                new VerzoekOpties(false, null, "handboek"));
        JsonNode j1 = instantie(handboek, "J1");
        assertEquals(0, new BigDecimal("13.75").compareTo(j1.at("/attributen/uitkering").decimalValue()), j1.toString());
        assertEquals("wrakhout", j1.at("/attributen/houtsoort").asText(), "'hout' is in het handboek de wrakhout");
        assertTrue(j1.at("/kenmerken/houtjutter").asBoolean(), j1.toString());

        ObjectNode canoniek = DUBBELZINNIG.verwerk("uitkering", VocabulaireBron.json("""
            { "instanties": [ { "type": "Jutter", "id": "J1",
                "attributen": { "vondstbedrag": 1, "premiebedrag": 2, "basisbedrag": 3, "toeslag": 4, "houtsoort": "hout" } } ] }"""),
                new VerzoekOpties(false, null, "canoniek"));
        assertEquals(0, new BigDecimal("10").compareTo(instantie(canoniek, "J1").at("/attributen/uitkering").decimalValue()));
        assertEquals("hout", instantie(canoniek, "J1").at("/attributen/houtsoort").asText());
    }

    @Test
    void onder_een_vastgezet_vocabulaire_is_een_andere_spelling_onbekend() {
        DienstFout f = assertThrows(DienstFout.class, () -> GOLDEN.verwerk("premie",
                VocabulaireBron.json(VocabulaireBron.CASE1), new VerzoekOpties(false, null, "handboek")));
        List<String> berichten = f.schendingen().stream().map(Schending::bericht).toList();
        assertTrue(berichten.contains("onbekende parameter 'premietarief' (invoervocabulaire 'handboek')"), berichten.toString());
        assertTrue(berichten.contains("onbekend objecttype 'Jutter' (invoervocabulaire 'handboek')"), berichten.toString());

        DienstFout waarde = assertThrows(DienstFout.class, () -> GOLDEN.verwerk("premie", VocabulaireBron.json("""
            { "instanties": [ { "type": "aangespoeld voorwerp", "id": "V1", "attributen": { "soort vondst": "barnsteen" } } ] }"""),
                new VerzoekOpties(false, null, "handboek")));
        Schending s = enige(waarde);
        assertEquals(Schending.Soort.ONBEKENDE_ENUM_WAARDE, s.soort());
        assertEquals("'barnsteen' is geen waarde van domein 'Vondstsoort' (toegestaan in het invoervocabulaire"
                + " 'handboek': [hout van een wrak, amber, bericht in een fles])", s.bericht());
    }

    @Test
    void twee_spellingen_van_een_declaratie_in_een_object_zijn_twee_waarden() {
        DienstFout f = assertThrows(DienstFout.class, () -> GOLDEN.verwerk("premie", VocabulaireBron.json("""
            { "instanties": [ { "type": "Vondst", "id": "V1", "attributen": { "gewicht": 1, "GEWICHT": 2 } } ] }""")));
        Schending s = enige(f);
        assertEquals(Schending.Soort.MALFORMED, s.soort());
        assertEquals("instanties[0].attributen.GEWICHT", s.veld());
        assertEquals("het attribuut 'gewicht' van 'Vondst' staat twee keer in deze instantie: als 'gewicht' en als"
                + " 'GEWICHT'", s.bericht());
    }

    @Test
    void een_onbekend_vocabulaire_is_een_eigen_weigering() {
        DienstFout f = assertThrows(DienstFout.class, () -> GOLDEN.verwerk("premie",
                VocabulaireBron.json(VocabulaireBron.CASE1), new VerzoekOpties(false, "wetboek", "Handboek2")));
        assertEquals(2, f.schendingen().size(), f.getMessage());
        assertEquals(Schending.Soort.ONBEKEND_VOCABULAIRE, f.schendingen().get(0).soort());
        assertEquals("vocabulaire", f.schendingen().get(0).veld());
        assertEquals("het vocabulaire 'wetboek' is niet gedeclareerd; het model declareert 'handboek' en 'cgm', en"
                + " 'canoniek' kiest de canonieke namen", f.schendingen().get(0).bericht());
        assertEquals("invoervocabulaire", f.schendingen().get(1).veld());
    }

    @Test
    void een_onbekende_naam_houdt_de_bestaande_weigering() {
        DienstFout f = assertThrows(DienstFout.class, () -> GOLDEN.verwerk("premie", VocabulaireBron.json("""
            { "instanties": [ { "type": "strandjutter", "id": "J1", "attributen": { "schoenmaat": 42 } } ] }""")));
        Schending s = enige(f);
        assertEquals(Schending.Soort.ONBEKEND_VELD, s.soort());
        assertEquals("onbekend attribuut 'schoenmaat' op objecttype 'Jutter'", s.bericht());
    }
}
