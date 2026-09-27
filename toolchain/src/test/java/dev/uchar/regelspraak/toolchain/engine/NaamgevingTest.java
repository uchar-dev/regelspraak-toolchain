package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The names of a model's declarations in a chosen vocabulary ({@link Naamgeving}), over own material: a designation
 * where the vocabulary has one, the name as given where it has none or where the name is no declaration, and the
 * canonical choice that changes nothing. An inherited attribute is named by the designation on the type that declares
 * it, a form keeps its version suffix, and an enumeration value is found as uchar compares declared names.
 */
class NaamgevingTest {

    static final String BRON = """
            Vocabulaire handboek (taal: nl)
            Vocabulaire cgm

            Domein Vondstsoort is van het type Enumeratie
                'barnsteen' (handboek: amber) (cgm: BARNSTEEN)
                'wrakhout'

            Objecttype de Strandvondst (mv: Strandvondsten) (handboek: aangespoeld voorwerp) (cgm: VONDST)
                het gewicht (handboek: gewicht in kilogram) Numeriek (getal met 2 decimalen);
                de soort Vondstsoort;
                is waardevol (cgm: WAARDEVOL) kenmerk (bijvoeglijk);

            Objecttype het Wrakstuk is een Strandvondst (mv: Wrakstukken) (handboek: wrak)
                de diepte Numeriek (geheel getal);

            Regel waardevolle vondst (cgm: R_WAARDEVOL)
                geldig altijd
                    Een Strandvondst is waardevol indien zijn soort gelijk is aan 'barnsteen'.
            """;

    private static RegelSpraakModel model() {
        RegelSpraakModel m = Resolver.resolve(BRON);
        assertTrue(m.isOk(), () -> "de eigen bron resolvet niet: " + m.errors());
        return m;
    }

    @Test
    void een_vocabulaire_noemt_de_benaming_en_valt_terug_op_de_naam() {
        RegelSpraakModel m = model();
        Naamgeving handboek = Naamgeving.van(m, "Handboek");
        assertEquals("handboek", handboek.keuze(), "de gedeclareerde spelling van het vocabulaire");
        assertEquals("aangespoeld voorwerp", handboek.objecttype("Strandvondst"));
        assertEquals("wrak", handboek.objecttype("Wrakstuk"));
        // the subtype's inherited attribute carries the designation written on its declaring supertype
        RegelSpraakModel.Objecttype wrakstuk = m.objecttypeOpNaam("Wrakstuk");
        assertEquals("gewicht in kilogram", handboek.attribuut(wrakstuk, "gewicht"));
        assertEquals("diepte", handboek.attribuut(wrakstuk, "diepte"), "zonder benaming: de naam zoals gegeven");
        assertEquals("waardevol", handboek.kenmerk(wrakstuk, "waardevol"));
        assertEquals("onbekend", handboek.attribuut(wrakstuk, "onbekend"), "geen declaratie: de naam zoals gegeven");
    }

    @Test
    void een_vorm_houdt_zijn_versieachtervoegsel_en_een_waarde_wordt_genormaliseerd_gevonden() {
        RegelSpraakModel m = model();
        Naamgeving cgm = Naamgeving.van(m, "cgm");
        assertEquals("R_WAARDEVOL", cgm.vorm("waardevolle vondst"));
        assertEquals("R_WAARDEVOL #2", cgm.vorm("waardevolle vondst #2"));
        assertEquals("een regel van de backend", cgm.vorm("een regel van de backend"));
        Datatype.Enumeratie soort = (Datatype.Enumeratie) m.domeinen.get("Vondstsoort");
        assertEquals("BARNSTEEN", cgm.waarde(soort, "Barnsteen"));
        assertEquals("amber", Naamgeving.van(m, "handboek").waarde(soort, "barnsteen"));
        assertEquals("wrakhout", cgm.waarde(soort, "wrakhout"), "zonder benaming: de waarde zoals gegeven");
    }

    @Test
    void canoniek_laat_elke_naam_zoals_hij_gegeven_is() {
        RegelSpraakModel m = model();
        Naamgeving canoniek = Naamgeving.van(m, "canoniek");
        assertTrue(canoniek.isCanoniek());
        assertEquals("canoniek", canoniek.keuze());
        assertEquals("strandvondst", canoniek.objecttype("strandvondst"));
        assertEquals("waardevolle vondst #2", canoniek.vorm("waardevolle vondst #2"));
        assertTrue(Naamgeving.van(m, null).isCanoniek());
        assertFalse(Naamgeving.van(m, "cgm").isCanoniek());
    }

    @Test
    void een_onbekend_vocabulaire_noemt_de_gedeclareerde() {
        Naamgeving.Vocabulairefout f = assertThrows(Naamgeving.Vocabulairefout.class,
                () -> Naamgeving.van(model(), "wetboek"));
        assertEquals("het vocabulaire 'wetboek' is niet gedeclareerd; het model declareert 'handboek' en 'cgm', en"
                + " 'canoniek' kiest de canonieke namen", f.getMessage());
        Naamgeving.Vocabulairefout zonder = assertThrows(Naamgeving.Vocabulairefout.class,
                () -> Naamgeving.van(Resolver.resolve("Objecttype de Jutter (mv: Jutters)\n    de naam Tekst;\n"), "cgm"));
        assertEquals("het vocabulaire 'cgm' is niet gedeclareerd; het model declareert geen vocabulaire, en"
                + " 'canoniek' kiest de canonieke namen", zonder.getMessage());
    }

    @Test
    void een_vocabulaire_met_de_naam_canoniek_kan_niet_gekozen_worden() {
        RegelSpraakModel m = Resolver.resolve("""
                Vocabulaire Canoniek
                Objecttype de Jutter (mv: Jutters) (Canoniek: strandjutter)
                    de naam Tekst;
                """);
        assertTrue(m.isOk(), () -> m.errors().toString());
        Naamgeving.Vocabulairefout f = assertThrows(Naamgeving.Vocabulairefout.class,
                () -> Naamgeving.van(m, "canoniek"));
        assertEquals("het model declareert een vocabulaire 'Canoniek', en 'canoniek' kiest de canonieke namen;"
                + " geef het vocabulaire een andere naam", f.getMessage());
        assertEquals(f.getMessage(), Naamgeving.gereserveerd(m));
    }
}
