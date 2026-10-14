package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.toolchain.service.TokaBron;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the playground gives per scenario beside uchar's report ({@link ScenarioGegevens}): the situation uchar's
 * scenario resolver makes, the source line of each expectation, and the fixpoint of the case's own session, with the
 * made objects, the links, the faults and the inconsistencies; and, at the root, the vocabularies. The existing keys
 * of a scenario keep their form, two analyses of one source give the same tree, and the names follow the chosen
 * vocabulary with the canonical name beside them where it differs.
 */
class PlaygroundScenarioGegevensTest {

    private static final String BERGING = """
            Eenheidsysteem Valuta
                de euro (mv: euros) EUR €
            Objecttype de Berging (mv: Bergingen)
                de opbrengst Numeriek (geheel getal) met eenheid €;
                het helpersaantal Numeriek (geheel getal);
                het helpersdeel Numeriek (geheel getal) met eenheid €;
                de premie Numeriek (getal met 2 decimalen) met eenheid €;
            Parameter het premiepercentage : Percentage (geheel getal)
            Regel premie van een berging
            geldig altijd
            De premie van een Berging moet berekend worden als het premiepercentage van de opbrengst van de Berging.
            Regel helpersdeel van een berging
            geldig altijd
            Het helpersdeel van een Berging moet berekend worden als de opbrengst van de Berging gedeeld door het helpersaantal van de Berging.
            """;

    private static JsonNode scenario(ObjectNode a, int i) {
        return a.at("/scenario/resultaten/" + i);
    }

    private static int regelVan(String bron, String tekst) {
        List<String> regels = List.of(bron.split("\n", -1));
        for (int i = 0; i < regels.size(); i++) if (regels.get(i).strip().equals(tekst)) return i + 1;
        throw new AssertionError("geen regel '" + tekst + "' in de bron");
    }

    private static List<String> namen(JsonNode lijst, String sleutel) {
        List<String> uit = new ArrayList<>();
        for (JsonNode n : lijst) uit.add(n.get(sleutel).asText());
        return uit;
    }

    /** The situation with its objects, values, units, roles, parameters and source lines, the expectation with its
     *  line beside uchar's short form, and the fixpoint with every value, input and derived, the derived value without
     *  an expectation among them. */
    @Test
    void situatie_verwachting_en_fixpunt() {
        String bron = BERGING + """
            Situatie basis:
            een Berging B
            - de opbrengst is 100 €
            - het helpersaantal is 2
            de parameter het premiepercentage is 20 %
            Scenario twee helpers
            met peildatum 01-01-2026
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - de premie van B is 20,00 €
            """;
        JsonNode s = scenario(Playground.analyseer(bron), 0);
        assertEquals("PASS", s.get("status").asText(), s.toString());
        assertEquals(regelVan(bron, "Scenario twee helpers"), s.get("regel").asInt());

        JsonNode v = s.at("/verwachtingen/0");
        assertEquals("attributeValue B premie", v.get("bron").asText(), "bron blijft uchars korte vorm");
        assertEquals(regelVan(bron, "- de premie van B is 20,00 €"), v.get("regel").asInt(), v.toString());
        assertEquals("- de premie van B is 20,00 €", v.get("regeltekst").asText());

        JsonNode sit = s.get("situatie");
        assertEquals("2026-01-01", sit.get("peildatum").asText());
        JsonNode b = sit.at("/objecten/0");
        assertEquals("B", b.get("label").asText());
        assertEquals("Berging", b.get("objecttype").asText());
        assertEquals(List.of("opbrengst", "helpersaantal"), namen(b.get("attributen"), "attribuut"));
        assertEquals("100", b.at("/attributen/0/waarde").asText());
        assertEquals("EUR", b.at("/attributen/0/eenheid").asText(), b.toString());
        assertEquals(regelVan(bron, "- de opbrengst is 100 €"), b.at("/attributen/0/regel").asInt());
        JsonNode p = sit.at("/parameters/0");
        assertEquals("premiepercentage", p.get("parameter").asText(), p.toString());
        assertEquals("20", p.get("waarde").asText());
        assertEquals("%", p.get("eenheid").asText());

        JsonNode f = s.get("fixpunt");
        assertFalse(s.has("fixpuntFout"), s.toString());
        JsonNode fb = f.at("/objecten/0");
        assertEquals("B", fb.get("object").asText());
        List<String> rijen = new ArrayList<>();
        for (JsonNode w : fb.get("waarden"))
            rijen.add(w.get("attribuut").asText() + "=" + w.get("waarde").asText() + " " + w.get("herkomst").asText());
        assertEquals(List.of("opbrengst=100 invoer", "helpersaantal=2 invoer", "helpersdeel=50 afgeleid",
                "premie=20 afgeleid"), rijen);
        assertEquals("EUR", fb.at("/waarden/3/eenheid").asText(), fb.toString());
        for (String lijst : new String[]{"gemaakteObjecten", "koppelingen", "fouten", "inconsistenties"})
            assertEquals(0, f.get(lijst).size(), lijst + ": " + f);
    }

    /** A division by zero: uchar's replay does not play the case out, the explanation stays withheld with its sentence,
     *  and the fixpoint gives the fault as data, with its kind, the form, the object by its label and uchar's sentence. */
    @Test
    void een_evaluatiefout_staat_als_gegeven_in_het_fixpunt() {
        JsonNode s = scenario(Playground.analyseer(BERGING + """
            Scenario zonder helpers
            Gegeven is de volgende situatie:
            een Berging B
            - de opbrengst is 100 €
            - het helpersaantal is 0
            de parameter het premiepercentage is 20 %
            Met als verwacht resultaat:
            - de premie van B is 20,00 €
            """), 0);
        assertEquals("FAIL", s.get("status").asText(), s.toString());
        assertTrue(s.get("uitlegFout").asText().startsWith("uchars replay speelde de case niet uit: "), s.toString());
        JsonNode fout = s.at("/fixpunt/fouten/0");
        assertEquals("DELING_DOOR_NUL", fout.get("soort").asText(), s.toString());
        assertEquals("helpersdeel van een berging", fout.get("regel").asText());
        assertEquals("B", fout.get("onderwerp").asText());
        assertTrue(fout.get("zin").asText().contains("deling door nul"), fout.toString());
        assertEquals(1, s.at("/fixpunt/fouten").size());
    }

    /** uchar's golden {@code jutterswet-strandmelding} creates a Strandrecht per Melding. The made objects follow the
     *  input in the order of their subject, not of the fixpoint, each with the value its creation initialised, and the
     *  links name them by that place. */
    @Test
    void gemaakte_objecten_en_koppelingen() throws IOException {
        String bron = Files.readString(TokaBron.REPO.resolve("uchar/corpus/golden/jutterswet-strandmelding/bron.rgs")) + """

            Scenario twee meldingen
            Gegeven is de volgende situatie:
            een Melding M2
            - de omvang is 20,00
            een Melding M1
            - de omvang is 10,00
            Met als verwacht resultaat:
            - de gemelde uitkering van M1 is 10,00
            """;
        ObjectNode a = Playground.analyseer(bron);
        JsonNode s = scenario(a, 0);
        assertEquals("PASS", s.get("status").asText(), s.toString());
        JsonNode f = s.get("fixpunt");
        assertEquals(List.of("M2", "M1", "#1", "#2"), namen(f.get("objecten"), "object"));
        assertEquals("creatie", f.at("/objecten/2/waarden/0/herkomst").asText(), f.toString());
        assertEquals("10.00", f.at("/objecten/2/waarden/0/waarde").asText());
        JsonNode g = f.get("gemaakteObjecten");
        assertEquals(List.of("#1", "#2"), namen(g, "object"));
        assertEquals(List.of("M1", "M2"), namen(g, "onderwerp"));
        assertEquals("maak een strandrecht bij elke melding", g.at("/0/creatie").asText());
        assertEquals("Strandrecht", g.at("/0/objecttype").asText());
        JsonNode k = f.get("koppelingen");
        assertEquals(2, k.size(), k.toString());
        assertEquals("strandmelding", k.at("/0/feittype").asText());
        assertEquals("melding", k.at("/0/eersteRol").asText());
        assertEquals("M1", k.at("/0/eerste").asText());
        assertEquals("gemeld strandrecht", k.at("/0/tweedeRol").asText());
        assertEquals("#1", k.at("/0/tweede").asText());
        assertEquals(a, Playground.analyseer(bron), "twee analyses van één bron geven dezelfde boom");
    }

    /** uchar's golden {@code jutterswet-opgave-bedrag}: an opgave that differs from the computed value gives an
     *  inconsistency on its object, and the characteristics derived beside it. */
    @Test
    void inconsistenties_en_afgeleide_kenmerken() throws IOException {
        String bron = Files.readString(TokaBron.REPO.resolve("uchar/corpus/golden/jutterswet-opgave-bedrag/bron.rgs")) + """

            Scenario opgave wijkt af
            Gegeven is de volgende situatie:
            een Vondst V
            - de taxatie is 100,00 €
            - de bergingskosten is 10,00 €
            - de opgegeven waarde is 95,00 €
            Met als verwacht resultaat:
            - de waarde van V is 95,00 €
            """;
        JsonNode s = scenario(Playground.analyseer(bron), 0);
        assertEquals("PASS", s.get("status").asText(), s.toString());
        JsonNode inc = s.at("/fixpunt/inconsistenties");
        assertEquals(1, inc.size(), inc.toString());
        assertEquals("controle opgave waarde", inc.at("/0/regel").asText());
        assertEquals("V", inc.at("/0/onderwerp").asText());
        JsonNode kenmerken = s.at("/fixpunt/objecten/0/kenmerken");
        assertEquals(List.of("ter controle", "afwijkende opgave waarde"), namen(kenmerken, "kenmerk"));
        assertEquals(List.of("afgeleid", "afgeleid"), namen(kenmerken, "herkomst"));
    }

    /** uchar's golden {@code jutterswet-vocabulaires} with the playground's scenario: the vocabularies at the root, and
     *  in {@code cgm} the situation and the fixpoint name every declaration there, the canonical name beside it. In the
     *  canonical choice no {@code …Canoniek} key appears. */
    @Test
    void vocabulaires_en_namen() throws IOException {
        String bron = Files.readString(TokaBron.REPO.resolve("uchar/corpus/golden/jutterswet-vocabulaires/bron.rgs"))
                + "\n" + Files.readString(Path.of("src/test/resources/vocabulaires/jutterswet-vocabulaires.scenario.rgs"));
        ObjectNode cgm = Playground.analyseer(bron, "cgm");
        JsonNode voc = cgm.get("vocabulaires");
        assertEquals("cgm", voc.get("gekozen").asText());
        assertEquals(List.of("handboek", "cgm"), namen(voc.get("gedeclareerd"), "naam"));
        assertTrue(voc.at("/gedeclareerd/1/standaard").asBoolean());
        JsonNode jutter = null;
        for (JsonNode e : voc.get("elementen"))
            if (e.get("soort").asText().equals("OBJECTTYPE") && e.get("canoniek").asText().equals("Jutter")) jutter = e;
        assertTrue(jutter != null, voc.toString());
        assertEquals("JUTTER", jutter.at("/benamingen/cgm").asText());
        assertEquals("strandjutter", jutter.at("/benamingen/handboek").asText());

        JsonNode s = scenario(cgm, 0);
        JsonNode v1 = s.at("/situatie/objecten/1");
        assertEquals("V1", v1.get("label").asText());
        assertEquals("VONDST", v1.get("objecttype").asText());
        assertEquals("Vondst", v1.get("objecttypeCanoniek").asText());
        assertEquals("VONDST_SOORT", v1.at("/attributen/0/attribuut").asText());
        assertEquals("BARNSTEEN", v1.at("/attributen/0/waarde").asText(), v1.toString());
        assertEquals("barnsteen", v1.at("/attributen/0/waardeCanoniek").asText());
        assertEquals("AANMELDER", v1.at("/rollen/0/rol").asText(), v1.toString());
        assertEquals("PREMIE_TARIEF", s.at("/situatie/parameters/0/parameter").asText());
        JsonNode fv1 = s.at("/fixpunt/objecten/1");
        assertTrue(fv1.toString().contains("\"attribuut\":\"PREMIE\",\"attribuutCanoniek\":\"premie\""), fv1.toString());
        assertTrue(fv1.toString().contains("\"kenmerk\":\"WAARDEVOL\""), fv1.toString());

        ObjectNode canoniek = Playground.analyseer(bron);
        assertEquals("canoniek", canoniek.at("/vocabulaires/gekozen").asText());
        assertFalse(canoniek.at("/scenario/resultaten/0/situatie").toString().contains("Canoniek\""),
                "in de canonieke keuze staat er geen tweede naam");
        assertFalse(canoniek.at("/scenario/resultaten/0/fixpunt").toString().contains("Canoniek\""));
    }

    /** A source without a vocabulary lists none and no declarations. */
    @Test
    void zonder_vocabulaire_geen_elementen() {
        JsonNode voc = Playground.analyseer(BERGING).get("vocabulaires");
        assertEquals("canoniek", voc.get("gekozen").asText());
        assertEquals(0, voc.get("gedeclareerd").size());
        assertEquals(0, voc.get("elementen").size());
    }
}
