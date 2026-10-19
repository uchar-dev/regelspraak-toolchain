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
 * The names of a model's declarations in a chosen vocabulary ({@link Naming}), over own material: a designation
 * where the vocabulary has one, the name as given where it has none or where the name is no declaration, and the
 * canonical choice that changes nothing. An inherited attribute is named by the designation on the type that declares
 * it, a form keeps its version suffix, and an enumeration value is found as uchar compares declared names.
 */
class NamingTest {

    static final String SOURCE = """
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
        RegelSpraakModel m = Resolver.resolve(SOURCE);
        assertTrue(m.isOk(), () -> "de eigen bron resolvet niet: " + m.errors());
        return m;
    }

    @Test
    void a_vocabulary_names_the_designation_and_falls_back_on_the_name() {
        RegelSpraakModel m = model();
        Naming handboek = Naming.of(m, "Handboek");
        assertEquals("handboek", handboek.choice(), "de gedeclareerde spelling van het vocabulaire");
        assertEquals("aangespoeld voorwerp", handboek.objectType("Strandvondst"));
        assertEquals("wrak", handboek.objectType("Wrakstuk"));
        // the subtype's inherited attribute carries the designation written on its declaring supertype
        RegelSpraakModel.ObjectType wrakstuk = m.objectTypeByName("Wrakstuk");
        assertEquals("gewicht in kilogram", handboek.attribute(wrakstuk, "gewicht"));
        assertEquals("diepte", handboek.attribute(wrakstuk, "diepte"), "zonder benaming: de naam zoals gegeven");
        assertEquals("waardevol", handboek.characteristic(wrakstuk, "waardevol"));
        assertEquals("onbekend", handboek.attribute(wrakstuk, "onbekend"), "geen declaratie: de naam zoals gegeven");
    }

    @Test
    void a_form_keeps_its_version_suffix_and_a_value_is_found_normalised() {
        RegelSpraakModel m = model();
        Naming cgm = Naming.of(m, "cgm");
        assertEquals("R_WAARDEVOL", cgm.form("waardevolle vondst"));
        assertEquals("R_WAARDEVOL #2", cgm.form("waardevolle vondst #2"));
        assertEquals("een regel van de backend", cgm.form("een regel van de backend"));
        Datatype.Enumeration kind = (Datatype.Enumeration) m.domains.get("Vondstsoort");
        assertEquals("BARNSTEEN", cgm.value(kind, "Barnsteen"));
        assertEquals("amber", Naming.of(m, "handboek").value(kind, "barnsteen"));
        assertEquals("wrakhout", cgm.value(kind, "wrakhout"), "zonder benaming: de waarde zoals gegeven");
    }

    @Test
    void canonical_lets_every_name_be_as_given() {
        RegelSpraakModel m = model();
        Naming canonical = Naming.of(m, "canonical");
        assertTrue(canonical.isCanonical());
        assertEquals("canonical", canonical.choice());
        assertEquals("strandvondst", canonical.objectType("strandvondst"));
        assertEquals("waardevolle vondst #2", canonical.form("waardevolle vondst #2"));
        assertTrue(Naming.of(m, null).isCanonical());
        assertFalse(Naming.of(m, "cgm").isCanonical());
    }

    @Test
    void an_unknown_vocabulary_names_the_declared_one() {
        Naming.VocabularyError f = assertThrows(Naming.VocabularyError.class,
                () -> Naming.of(model(), "wetboek"));
        assertEquals("het vocabulaire 'wetboek' is niet gedeclareerd; het model declareert 'handboek' en 'cgm', en"
                + " 'canonical' kiest de canonieke namen", f.getMessage());
        Naming.VocabularyError without = assertThrows(Naming.VocabularyError.class,
                () -> Naming.of(Resolver.resolve("Objecttype de Jutter (mv: Jutters)\n    de naam Tekst;\n"), "cgm"));
        assertEquals("het vocabulaire 'cgm' is niet gedeclareerd; het model declareert geen vocabulaire, en"
                + " 'canonical' kiest de canonieke namen", without.getMessage());
    }

    @Test
    void a_vocabulary_with_the_name_canonical_cannot_be_chosen() {
        RegelSpraakModel m = Resolver.resolve("""
                Vocabulaire Canonical
                Objecttype de Jutter (mv: Jutters) (Canonical: strandjutter)
                    de naam Tekst;
                """);
        assertTrue(m.isOk(), () -> m.errors().toString());
        Naming.VocabularyError f = assertThrows(Naming.VocabularyError.class,
                () -> Naming.of(m, "canonical"));
        assertEquals("het model declareert een vocabulaire 'Canonical', en 'canonical' kiest de canonieke namen;"
                + " geef het vocabulaire een andere naam", f.getMessage());
        assertEquals(f.getMessage(), Naming.reserved(m));
    }
}
