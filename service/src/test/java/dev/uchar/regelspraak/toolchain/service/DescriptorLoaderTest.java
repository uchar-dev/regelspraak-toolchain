package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Descriptor parsing + validation against the resolved TOKA model (decision 1). */
class DescriptorLoaderTest {

    private static RegelSpraakModel MODEL;

    @BeforeAll
    static void resolveer() {
        StringBuilder bron = new StringBuilder();
        for (String tekst : TokaBron.bronPerBestand().values()) bron.append(tekst).append('\n');
        MODEL = Resolver.resolve(bron.toString());
        assertTrue(MODEL.errors().isEmpty(), "TOKA-bron hoort schoon te resolven: " + MODEL.errors());
    }

    @Test
    void toka_descriptor_valideert_schoon() {
        DienstDescriptor d = DescriptorLoader.laad(TokaBron.descriptorJson(), MODEL);
        assertEquals("TOKA belastingdienst", d.dienst().naam());
        assertEquals(29, d.entrypoints().size(), "de gepinde TOKA-descriptor hoort 29 entrypoints te hebben "
                + "— precies de 29 exit-0 golden scenariotoetsen (30 tot besluit 16Y toka-45 liet vervallen, "
                + "zie TokaBron), inclusief toka-29 'passagier met recht op "
                + "treinmiles' (een §13.4.7 feitcreatie: nu benoembaar via de RAW model.regels, ook al levert "
                + "hij geen resolved-collectie-regel op)");
        // no violations
        assertTrue(DescriptorLoader.valideer(d, MODEL).isEmpty());
    }

    @Test
    void onbekende_regel_wordt_genoemd() {
        String json = """
            { "dienst": {"naam":"t","versie":"1"},
              "entrypoints": [ { "naam":"x","pad":"x",
                "scope": {"regelnamen": ["deze regel bestaat niet"]},
                "invoer": [], "uitvoer": [] } ], "parameters": {} }""";
        DienstFout f = assertThrows(DienstFout.class, () -> DescriptorLoader.laad(json, MODEL));
        assertTrue(f.getMessage().contains("deze regel bestaat niet"), f.getMessage());
        assertEquals(Schending.Soort.ONBEKENDE_VERWIJZING, f.schendingen().get(0).soort());
        assertTrue(f.schendingen().get(0).veld().contains("scope.regelnamen[0]"));
    }

    @Test
    void onbekend_objecttype_in_invoer_wordt_genoemd() {
        String json = """
            { "dienst": {"naam":"t","versie":"1"},
              "entrypoints": [ { "naam":"x","pad":"x",
                "scope": {"regelnamen": ["bepaal leeftijd"]},
                "invoer": ["Ruimteschip"], "uitvoer": [] } ], "parameters": {} }""";
        DienstFout f = assertThrows(DienstFout.class, () -> DescriptorLoader.laad(json, MODEL));
        assertTrue(f.getMessage().contains("Ruimteschip"), f.getMessage());
        assertTrue(f.schendingen().stream().anyMatch(s -> s.veld().equals("entrypoints[0].invoer[0]")));
    }

    @Test
    void onbekend_attribuut_in_uitvoer_wordt_genoemd() {
        String json = """
            { "dienst": {"naam":"t","versie":"1"},
              "entrypoints": [ { "naam":"x","pad":"x",
                "scope": {"regelnamen": ["bepaal leeftijd"]},
                "invoer": [], "uitvoer": ["Natuurlijk persoon.schoenmaat"] } ], "parameters": {} }""";
        DienstFout f = assertThrows(DienstFout.class, () -> DescriptorLoader.laad(json, MODEL));
        assertTrue(f.getMessage().contains("schoenmaat"), f.getMessage());
    }

    @Test
    void onbekend_kenmerk_in_uitvoer_wordt_genoemd() {
        String json = """
            { "dienst": {"naam":"t","versie":"1"},
              "entrypoints": [ { "naam":"x","pad":"x",
                "scope": {"regelnamen": ["bepaal leeftijd"]},
                "invoer": [], "uitvoer": ["Natuurlijk persoon:onsterfelijk"] } ], "parameters": {} }""";
        DienstFout f = assertThrows(DienstFout.class, () -> DescriptorLoader.laad(json, MODEL));
        assertTrue(f.getMessage().contains("onsterfelijk"), f.getMessage());
    }

    @Test
    void onbekende_parameter_wordt_genoemd() {
        String json = """
            { "dienst": {"naam":"t","versie":"1"},
              "entrypoints": [ { "naam":"x","pad":"x",
                "scope": {"regelnamen": ["bepaal leeftijd"]},
                "invoer": [], "uitvoer": [] } ],
              "parameters": { "belastingtarief van pluto": 5 } }""";
        DienstFout f = assertThrows(DienstFout.class, () -> DescriptorLoader.laad(json, MODEL));
        assertTrue(f.getMessage().contains("belastingtarief van pluto"), f.getMessage());
        assertTrue(f.schendingen().stream().anyMatch(s -> s.veld().equals("parameters.belastingtarief van pluto")));
    }

    @Test
    void alle_schendingen_worden_verzameld_niet_een_voor_een() throws Exception {
        String json = """
            { "dienst": {"naam":"t","versie":"1"},
              "entrypoints": [ { "naam":"x","pad":"x",
                "scope": {"regelnamen": ["nep regel"]},
                "invoer": ["Nep type"], "uitvoer": ["Nep type.nep attribuut"] } ],
              "parameters": { "nep param": 1 } }""";
        DienstDescriptor d = DescriptorLoader.MAPPER.readValue(json, DienstDescriptor.class);
        List<Schending> s = DescriptorLoader.valideer(d, MODEL);
        // 4 distinct unknown references collected together (regel, invoer-type, uitvoer-type, parameter)
        assertTrue(s.size() >= 4, "verwacht >= 4 verzamelde schendingen, kreeg " + s);
        assertFalse(s.isEmpty());
    }

    @Test
    void malformed_json_geeft_MALFORMED_schending() {
        DienstFout f = assertThrows(DienstFout.class, () -> DescriptorLoader.laad("{ niet echt json", MODEL));
        assertEquals(Schending.Soort.MALFORMED, f.schendingen().get(0).soort());
    }
}
