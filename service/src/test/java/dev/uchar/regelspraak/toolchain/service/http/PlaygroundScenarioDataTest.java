package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.toolchain.service.TokaSource;

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
 * What the playground gives per scenario beside uchar's report ({@link ScenarioData}): the situation uchar's
 * scenario resolver makes, the source line of each expectation, and the fixpoint of the case's own session, with the
 * made objects, the links, the faults and the inconsistencies; and, at the root, the vocabularies. The existing keys
 * of a scenario keep their form, two analyses of one source give the same tree, and the names follow the chosen
 * vocabulary with the canonical name beside them where it differs.
 */
class PlaygroundScenarioDataTest {

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
        return a.at("/scenario/results/" + i);
    }

    private static int lineOf(String source, String text) {
        List<String> lines = List.of(source.split("\n", -1));
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).strip().equals(text)) return i + 1;
        throw new AssertionError("geen regel '" + text + "' in de bron");
    }

    private static List<String> names(JsonNode list, String key) {
        List<String> sink = new ArrayList<>();
        for (JsonNode n : list) sink.add(n.get(key).asText());
        return sink;
    }

    /** The situation with its objects, values, units, roles, parameters and source lines, the expectation with its
     *  line beside uchar's short form, and the fixpoint with every value, input and derived, the derived value without
     *  an expectation among them. */
    @Test
    void situation_expectation_and_fixpoint() {
        String source = BERGING + """
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
        JsonNode s = scenario(Playground.analyseer(source), 0);
        assertEquals("PASS", s.get("status").asText(), s.toString());
        assertEquals(lineOf(source, "Scenario twee helpers"), s.get("line").asInt());

        JsonNode v = s.at("/expectations/0");
        assertEquals("attributeValue B premie", v.get("source").asText(), "bron blijft uchars korte vorm");
        assertEquals(lineOf(source, "- de premie van B is 20,00 €"), v.get("line").asInt(), v.toString());
        assertEquals("- de premie van B is 20,00 €", v.get("lineText").asText());

        JsonNode sit = s.get("situation");
        assertEquals("2026-01-01", sit.get("asOfDate").asText());
        JsonNode b = sit.at("/objects/0");
        assertEquals("B", b.get("label").asText());
        assertEquals("Berging", b.get("objectType").asText());
        assertEquals(List.of("opbrengst", "helpersaantal"), names(b.get("attributes"), "attribute"));
        assertEquals("100", b.at("/attributes/0/value").asText());
        assertEquals("EUR", b.at("/attributes/0/unit").asText(), b.toString());
        assertEquals(lineOf(source, "- de opbrengst is 100 €"), b.at("/attributes/0/line").asInt());
        JsonNode p = sit.at("/parameters/0");
        assertEquals("premiepercentage", p.get("parameter").asText(), p.toString());
        assertEquals("20", p.get("value").asText());
        assertEquals("%", p.get("unit").asText());

        JsonNode f = s.get("fixpoint");
        assertFalse(s.has("fixpointError"), s.toString());
        JsonNode fb = f.at("/objects/0");
        assertEquals("B", fb.get("object").asText());
        List<String> rows = new ArrayList<>();
        for (JsonNode w : fb.get("values"))
            rows.add(w.get("attribute").asText() + "=" + w.get("value").asText() + " " + w.get("origin").asText());
        assertEquals(List.of("opbrengst=100 input", "helpersaantal=2 input", "helpersdeel=50 derived",
                "premie=20 derived"), rows);
        assertEquals("EUR", fb.at("/values/3/unit").asText(), fb.toString());
        for (String list : new String[]{"madeObjects", "links", "faults", "inconsistencies"})
            assertEquals(0, f.get(list).size(), list + ": " + f);
    }

    /** A division by zero: uchar's replay does not play the case out, the explanation stays withheld with its sentence,
     *  and the fixpoint gives the fault as data, with its kind, the form, the object by its label and uchar's sentence. */
    @Test
    void an_evaluation_fault_stands_as_given_in_the_fixpoint() {
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
        assertTrue(s.get("explanationError").asText().startsWith("uchars replay speelde de case niet uit: "), s.toString());
        JsonNode error = s.at("/fixpoint/faults/0");
        assertEquals("DIVISION_BY_ZERO", error.get("kind").asText(), s.toString());
        assertEquals("helpersdeel van een berging", error.get("rule").asText());
        assertEquals("B", error.get("subject").asText());
        assertTrue(error.get("sentence").asText().contains("deling door nul"), error.toString());
        assertEquals(1, s.at("/fixpoint/faults").size());
    }

    /** uchar's golden {@code jutterswet-strandmelding} creates a Strandrecht per Melding. The made objects follow the
     *  input in the order of their subject, not of the fixpoint, each with the value its creation initialised, and the
     *  links name them by that place. */
    @Test
    void made_objects_and_links() throws IOException {
        String source = Files.readString(TokaSource.REPO.resolve("uchar/corpus/golden/jutterswet-strandmelding/bron.rgs")) + """

            Scenario twee meldingen
            Gegeven is de volgende situatie:
            een Melding M2
            - de omvang is 20,00
            een Melding M1
            - de omvang is 10,00
            Met als verwacht resultaat:
            - de gemelde uitkering van M1 is 10,00
            """;
        ObjectNode a = Playground.analyseer(source);
        JsonNode s = scenario(a, 0);
        assertEquals("PASS", s.get("status").asText(), s.toString());
        JsonNode f = s.get("fixpoint");
        assertEquals(List.of("M2", "M1", "#1", "#2"), names(f.get("objects"), "object"));
        assertEquals("creation", f.at("/objects/2/values/0/origin").asText(), f.toString());
        assertEquals("10.00", f.at("/objects/2/values/0/value").asText());
        JsonNode g = f.get("madeObjects");
        assertEquals(List.of("#1", "#2"), names(g, "object"));
        assertEquals(List.of("M1", "M2"), names(g, "subject"));
        assertEquals("maak een strandrecht bij elke melding", g.at("/0/creation").asText());
        assertEquals("Strandrecht", g.at("/0/objectType").asText());
        JsonNode k = f.get("links");
        assertEquals(2, k.size(), k.toString());
        assertEquals("strandmelding", k.at("/0/factType").asText());
        assertEquals("melding", k.at("/0/firstRole").asText());
        assertEquals("M1", k.at("/0/first").asText());
        assertEquals("gemeld strandrecht", k.at("/0/secondRole").asText());
        assertEquals("#1", k.at("/0/second").asText());
        assertEquals(a, Playground.analyseer(source), "twee analyses van één bron geven dezelfde boom");
    }

    /** uchar's golden {@code jutterswet-opgave-bedrag}: an opgave that differs from the computed value gives an
     *  inconsistency on its object, and the characteristics derived beside it. */
    @Test
    void inconsistencies_and_derived_characteristics() throws IOException {
        String source = Files.readString(TokaSource.REPO.resolve("uchar/corpus/golden/jutterswet-opgave-bedrag/bron.rgs")) + """

            Scenario opgave wijkt af
            Gegeven is de volgende situatie:
            een Vondst V
            - de taxatie is 100,00 €
            - de bergingskosten is 10,00 €
            - de opgegeven waarde is 95,00 €
            Met als verwacht resultaat:
            - de waarde van V is 95,00 €
            """;
        JsonNode s = scenario(Playground.analyseer(source), 0);
        assertEquals("PASS", s.get("status").asText(), s.toString());
        JsonNode inc = s.at("/fixpoint/inconsistencies");
        assertEquals(1, inc.size(), inc.toString());
        assertEquals("controle opgave waarde", inc.at("/0/rule").asText());
        assertEquals("V", inc.at("/0/subject").asText());
        JsonNode characteristics = s.at("/fixpoint/objects/0/characteristics");
        assertEquals(List.of("ter controle", "afwijkende opgave waarde"), names(characteristics, "characteristic"));
        assertEquals(List.of("derived", "derived"), names(characteristics, "origin"));
    }

    /** uchar's golden {@code jutterswet-vocabulaires} with the playground's scenario: the vocabularies at the root, and
     *  in {@code cgm} the situation and the fixpoint name every declaration there, the canonical name beside it. In the
     *  canonical choice no {@code …Canoniek} key appears. */
    @Test
    void vocabularies_and_names() throws IOException {
        String source = Files.readString(TokaSource.REPO.resolve("uchar/corpus/golden/jutterswet-vocabulaires/bron.rgs"))
                + "\n" + Files.readString(Path.of("src/test/resources/vocabularies/jutterswet-vocabulaires.scenario.rgs"));
        ObjectNode cgm = Playground.analyseer(source, "cgm");
        JsonNode voc = cgm.get("vocabularies");
        assertEquals("cgm", voc.get("chosen").asText());
        assertEquals(List.of("handboek", "cgm"), names(voc.get("declared"), "name"));
        assertTrue(voc.at("/declared/1/default").asBoolean());
        JsonNode jutter = null;
        for (JsonNode e : voc.get("elements"))
            if (e.get("kind").asText().equals("OBJECTTYPE") && e.get("canonical").asText().equals("Jutter")) jutter = e;
        assertTrue(jutter != null, voc.toString());
        assertEquals("JUTTER", jutter.at("/designations/cgm").asText());
        assertEquals("strandjutter", jutter.at("/designations/handboek").asText());

        JsonNode s = scenario(cgm, 0);
        JsonNode v1 = s.at("/situation/objects/1");
        assertEquals("V1", v1.get("label").asText());
        assertEquals("VONDST", v1.get("objectType").asText());
        assertEquals("Vondst", v1.get("objectTypeCanonical").asText());
        assertEquals("VONDST_SOORT", v1.at("/attributes/0/attribute").asText());
        assertEquals("BARNSTEEN", v1.at("/attributes/0/value").asText(), v1.toString());
        assertEquals("barnsteen", v1.at("/attributes/0/valueCanonical").asText());
        assertEquals("AANMELDER", v1.at("/roles/0/role").asText(), v1.toString());
        assertEquals("PREMIE_TARIEF", s.at("/situation/parameters/0/parameter").asText());
        JsonNode fv1 = s.at("/fixpoint/objects/1");
        assertTrue(fv1.toString().contains("\"attribute\":\"PREMIE\",\"attributeCanonical\":\"premie\""), fv1.toString());
        assertTrue(fv1.toString().contains("\"characteristic\":\"WAARDEVOL\""), fv1.toString());

        ObjectNode canonical = Playground.analyseer(source);
        assertEquals("canonical", canonical.at("/vocabularies/chosen").asText());
        assertFalse(canonical.at("/scenario/results/0/situation").toString().contains("Canonical\""),
                "in de canonieke keuze staat er geen tweede naam");
        assertFalse(canonical.at("/scenario/results/0/fixpoint").toString().contains("Canonical\""));
    }

    /** A source without a vocabulary lists none; its declarations are there, each without a designation. */
    @Test
    void without_vocabulary_empty_designations() {
        JsonNode voc = Playground.analyseer(BERGING).get("vocabularies");
        assertEquals("canonical", voc.get("chosen").asText());
        assertEquals(0, voc.get("declared").size());
        assertEquals(8, voc.get("elements").size(), voc.toString());
        for (JsonNode e : voc.get("elements")) assertEquals(0, e.get("designations").size(), e.toString());
    }
}
