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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Each role in the playground's {@code situatie.objecten[].rollen[]} carries {@code feittype}: the fact type uchar's
 * scenario resolver found the role in, named like every other name of the situation (the chosen vocabulary, the
 * canonical name beside it where it differs). One role name can stand in several fact types; which one an
 * assignment means follows from the type of the object it stands on, and the playground passes on uchar's answer.
 */
class PlaygroundRolFeittypeTest {

    /** {@code vergunning} is a role of three fact types; a Jutter, a Vondst and a Strook each reach a Vergunning
     *  through another one. */
    private static final String VERGUND = """
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

    private static JsonNode object(JsonNode situatie, String label) {
        for (JsonNode o : situatie.get("objecten")) if (o.get("label").asText().equals(label)) return o;
        return fail("geen object " + label + " in " + situatie);
    }

    private static JsonNode rol(JsonNode situatie, String label, String rol) {
        for (JsonNode r : object(situatie, label).get("rollen")) if (r.get("rol").asText().equals(rol)) return r;
        return fail("object " + label + " heeft geen rol '" + rol + "' in " + situatie);
    }

    /** The fact type named {@code naam} in the IR document of the analysis, or null. */
    private static JsonNode irFeittype(JsonNode analyse, String naam) {
        for (JsonNode ft : analyse.at("/ir/model/factTypes")) if (ft.get("name").asText().equals(naam)) return ft;
        return null;
    }

    @Test
    void een_rolnaam_in_drie_feittypen() {
        ObjectNode a = Playground.analyseer(VERGUND);
        JsonNode s = a.at("/scenario/resultaten/0");
        assertEquals("PASS", s.get("status").asText(), s.toString());
        JsonNode sit = s.get("situatie");
        String[][] verwacht = {
                {"J", "vergunningen", "vergunninghouderschap"},
                {"V", "vergunning", "vergunde vondst"},
                {"S", "vergunning", "vergunde strook"},
                {"G", "houder", "vergunninghouderschap"},
                {"G", "vondsten", "vergunde vondst"},
                {"G", "strook", "vergunde strook"},
                {"J", "strandmaat", "strandmaatschap"}};
        for (String[] v : verwacht) {
            JsonNode r = rol(sit, v[0], v[1]);
            assertEquals(v[2], r.get("feittype").asText(), v[0] + " " + v[1] + ": " + r);
            assertFalse(r.has("feittypeCanoniek"), r.toString());
            assertTrue(irFeittype(a, v[2]) != null, v[2] + " staat niet in ir.model.factTypes");
        }
        assertEquals(List.of("rol", "feittype", "doeltype", "labels", "meervoudig", "regel"),
                velden(rol(sit, "V", "vergunning")), "feittype staat naast de bestaande sleutels");
    }

    /** In a vocabulary the fact type goes by its designation there, the canonical name beside it. */
    @Test
    void in_een_vocabulaire() throws IOException {
        String bron = Files.readString(TokaBron.REPO.resolve("uchar/corpus/golden/jutterswet-vocabulaires/bron.rgs"))
                + "\n" + Files.readString(Path.of("src/test/resources/vocabulaires/jutterswet-vocabulaires.scenario.rgs"));
        JsonNode cgm = rol(Playground.analyseer(bron, "cgm").at("/scenario/resultaten/0/situatie"), "V1", "AANMELDER");
        assertEquals("VONDST_AANMELDING", cgm.get("feittype").asText(), cgm.toString());
        assertEquals("vondstaanmelding", cgm.get("feittypeCanoniek").asText(), cgm.toString());
        JsonNode handboek = rol(Playground.analyseer(bron, "handboek").at("/scenario/resultaten/0/situatie"), "V1",
                "melder");
        assertEquals("aanmelding van een vondst", handboek.get("feittype").asText(), handboek.toString());
        JsonNode canoniek = rol(Playground.analyseer(bron).at("/scenario/resultaten/0/situatie"), "V1", "aanmelder");
        assertEquals("vondstaanmelding", canoniek.get("feittype").asText(), canoniek.toString());
    }

    /** Over every scenario golden of uchar: each role of each situation names a fact type of the IR document that
     *  has the role (by its name or plural) with the role's target type. */
    @Test
    void elke_scenariogolden() throws IOException {
        List<Path> mappen;
        try (Stream<Path> s = Files.list(TokaBron.REPO.resolve("uchar/corpus/golden"))) {
            mappen = s.filter(p -> Files.isRegularFile(p.resolve("scenario.rgs"))).sorted().toList();
        }
        assertTrue(mappen.size() >= 20, "het corpus telt " + mappen.size() + " scenario-goldens");
        int rollen = 0;
        List<String> fouten = new ArrayList<>();
        for (Path m : mappen) {
            String bron = Files.readString(m.resolve("bron.rgs")) + "\n" + Files.readString(m.resolve("scenario.rgs"));
            ObjectNode a = Playground.analyseer(bron);
            JsonNode resultaten = a.at("/scenario/resultaten");
            if (resultaten.isEmpty()) {
                fouten.add(m.getFileName() + ": geen scenarioresultaten: " + a.get("resolver"));
                continue;
            }
            for (JsonNode s : resultaten)
                for (JsonNode o : s.at("/situatie/objecten"))
                    for (JsonNode r : o.get("rollen")) {
                        rollen++;
                        String waar = m.getFileName() + " " + o.get("label").asText() + " " + r;
                        JsonNode ft = irFeittype(a, r.path("feittype").asText(null));
                        if (ft == null) {
                            fouten.add(waar + ": het feittype staat niet in ir.model.factTypes");
                            continue;
                        }
                        boolean gevonden = false;
                        for (JsonNode ir : ft.get("roles"))
                            gevonden |= (ir.get("name").asText().equals(r.get("rol").asText())
                                    || ir.path("plural").asText("").equals(r.get("rol").asText()))
                                    && ir.get("objectType").asText().equals(r.get("doeltype").asText());
                        if (!gevonden) fouten.add(waar + ": het feittype heeft die rol met dat doeltype niet");
                    }
        }
        assertEquals(List.of(), fouten);
        assertTrue(rollen > 100, rollen + " rollen getoetst");
    }

    private static List<String> velden(JsonNode n) {
        List<String> uit = new ArrayList<>();
        n.fieldNames().forEachRemaining(uit::add);
        return uit;
    }
}
