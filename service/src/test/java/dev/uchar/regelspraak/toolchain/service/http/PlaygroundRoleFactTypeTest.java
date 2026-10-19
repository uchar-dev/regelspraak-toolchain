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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Each role in the playground's {@code situation.objects[].roles[]} carries {@code feittype}: the fact type uchar's
 * scenario resolver found the role in, named like every other name of the situation (the chosen vocabulary, the
 * canonical name beside it where it differs). One role name can stand in several fact types; which one an
 * assignment means follows from the type of the object it stands on, and the playground passes on uchar's answer.
 */
class PlaygroundRoleFactTypeTest {

    /** {@code vergunning} is a role of three fact types; a Jutter, a Vondst and a Strook each reach a Vergunning
     *  through another one. */
    private static final String GRANTED = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                de naam Tekst;
            Objecttype de Vergunning (mv: Vergunningen)
                het nummer Numeriek (geheel getal);
            Objecttype de Vondst (mv: Vondsten)
                het gewicht Numeriek (getal);
            Objecttype de Strook (mv: Stroken)
                de lengte Numeriek (getal);
            Feittype vergunninghouderschap
                de houder Jutter
                de vergunning (mv: vergunningen) Vergunning
            Eén houder heeft meerdere vergunningen
            Feittype vergunde vondst
                de vergunning Vergunning
                de vondst (mv: vondsten) Vondst
            Eén vergunning heeft meerdere vondsten
            Feittype vergunde strook
                de vergunning Vergunning
                de strook Strook
            Eén vergunning heeft één strook
            Wederkerig feittype strandmaatschap
                de strandmaat (mv: strandmaten) Jutter
            Eén strandmaat heeft één strandmaat
            Regel naam van een jutter
            geldig altijd
                De naam van een Jutter moet gesteld worden op "jutter".
            Situatie vergund:
            een Jutter J
            - de vergunningen zijn G
            - de strandmaat is K
            een Jutter K
            een Vergunning G
            - de houder is J
            - de vondsten zijn V, W
            - de strook is S
            een Vondst V
            - de vergunning is G
            een Vondst W
            een Strook S
            - de vergunning is G
            Scenario vergund
            met peildatum 01-01-2026
            gebaseerd op de situatie vergund
            Met als verwacht resultaat:
            - de naam van J is "jutter"
            """;

    private static JsonNode object(JsonNode situation, String label) {
        for (JsonNode o : situation.get("objects")) if (o.get("label").asText().equals(label)) return o;
        return fail("geen object " + label + " in " + situation);
    }

    private static JsonNode role(JsonNode situation, String label, String role) {
        for (JsonNode r : object(situation, label).get("roles")) if (r.get("role").asText().equals(role)) return r;
        return fail("object " + label + " heeft geen rol '" + role + "' in " + situation);
    }

    /** The fact type named {@code naam} in the IR document of the analysis, or null. */
    private static JsonNode irFactType(JsonNode analyse, String name) {
        for (JsonNode ft : analyse.at("/ir/model/factTypes")) if (ft.get("name").asText().equals(name)) return ft;
        return null;
    }

    @Test
    void a_role_name_in_three_fact_types() {
        ObjectNode a = Playground.analyseer(GRANTED);
        JsonNode s = a.at("/scenario/results/0");
        assertEquals("PASS", s.get("status").asText(), s.toString());
        JsonNode sit = s.get("situation");
        String[][] expected = {
                {"J", "vergunningen", "vergunninghouderschap"},
                {"V", "vergunning", "vergunde vondst"},
                {"S", "vergunning", "vergunde strook"},
                {"G", "houder", "vergunninghouderschap"},
                {"G", "vondsten", "vergunde vondst"},
                {"G", "strook", "vergunde strook"},
                {"J", "strandmaat", "strandmaatschap"}};
        for (String[] v : expected) {
            JsonNode r = role(sit, v[0], v[1]);
            assertEquals(v[2], r.get("factType").asText(), v[0] + " " + v[1] + ": " + r);
            assertFalse(r.has("factTypeCanonical"), r.toString());
            assertTrue(irFactType(a, v[2]) != null, v[2] + " staat niet in ir.model.factTypes");
        }
        assertEquals(List.of("role", "factType", "targetType", "labels", "multiple", "line"),
                fields(role(sit, "V", "vergunning")), "feittype staat naast de bestaande sleutels");
    }

    /** In a vocabulary the fact type goes by its designation there, the canonical name beside it. */
    @Test
    void in_a_vocabulary() throws IOException {
        String source = Files.readString(TokaSource.REPO.resolve("uchar/corpus/golden/jutterswet-vocabulaires/source.rgs"))
                + "\n" + Files.readString(Path.of("src/test/resources/vocabularies/jutterswet-vocabulaires.scenario.rgs"));
        JsonNode cgm = role(Playground.analyseer(source, "cgm").at("/scenario/results/0/situation"), "V1", "AANMELDER");
        assertEquals("VONDST_AANMELDING", cgm.get("factType").asText(), cgm.toString());
        assertEquals("vondstaanmelding", cgm.get("factTypeCanonical").asText(), cgm.toString());
        JsonNode handboek = role(Playground.analyseer(source, "handboek").at("/scenario/results/0/situation"), "V1",
                "melder");
        assertEquals("aanmelding van een vondst", handboek.get("factType").asText(), handboek.toString());
        JsonNode canonical = role(Playground.analyseer(source).at("/scenario/results/0/situation"), "V1", "aanmelder");
        assertEquals("vondstaanmelding", canonical.get("factType").asText(), canonical.toString());
    }

    /** Over every scenario golden of uchar: each role of each situation names a fact type of the IR document that
     *  has the role (by its name or plural) with the role's target type. */
    @Test
    void every_scenario_golden() throws IOException {
        List<Path> directories;
        try (Stream<Path> s = Files.list(TokaSource.REPO.resolve("uchar/corpus/golden"))) {
            directories = s.filter(p -> Files.isRegularFile(p.resolve("scenario.rgs"))).sorted().toList();
        }
        assertTrue(directories.size() >= 20, "het corpus telt " + directories.size() + " scenario-goldens");
        int roles = 0;
        List<String> errors = new ArrayList<>();
        for (Path m : directories) {
            String source = Files.readString(m.resolve("source.rgs")) + "\n" + Files.readString(m.resolve("scenario.rgs"));
            ObjectNode a = Playground.analyseer(source);
            JsonNode results = a.at("/scenario/results");
            if (results.isEmpty()) {
                errors.add(m.getFileName() + ": geen scenarioresultaten: " + a.get("resolver"));
                continue;
            }
            for (JsonNode s : results)
                for (JsonNode o : s.at("/situation/objects"))
                    for (JsonNode r : o.get("roles")) {
                        roles++;
                        String where = m.getFileName() + " " + o.get("label").asText() + " " + r;
                        JsonNode ft = irFactType(a, r.path("factType").asText(null));
                        if (ft == null) {
                            errors.add(where + ": het feittype staat niet in ir.model.factTypes");
                            continue;
                        }
                        boolean found = false;
                        for (JsonNode ir : ft.get("roles"))
                            found |= (ir.get("name").asText().equals(r.get("role").asText())
                                    || ir.path("plural").asText("").equals(r.get("role").asText()))
                                    && ir.get("objectType").asText().equals(r.get("targetType").asText());
                        if (!found) errors.add(where + ": het feittype heeft die rol met dat doeltype niet");
                    }
        }
        assertEquals(List.of(), errors);
        assertTrue(roles > 100, roles + " rollen getoetst");
    }

    private static List<String> fields(JsonNode n) {
        List<String> sink = new ArrayList<>();
        n.fieldNames().forEachRemaining(sink::add);
        return sink;
    }
}
