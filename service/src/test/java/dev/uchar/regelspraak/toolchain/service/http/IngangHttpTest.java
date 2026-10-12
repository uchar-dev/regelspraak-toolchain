package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.emit.IrEmitter;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.runtime.Breuk;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.toolchain.engine.RolSchrijver;
import dev.uchar.regelspraak.toolchain.service.AntwoordBouwer;
import dev.uchar.regelspraak.toolchain.service.BeslisserKern;
import dev.uchar.regelspraak.toolchain.service.Evaluatiefout;
import dev.uchar.regelspraak.toolchain.service.KernConfig;
import dev.uchar.regelspraak.toolchain.service.Uitvoerder;
import dev.uchar.regelspraak.toolchain.service.Wereld;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service fills, fires and reads every session through uchar's entry ({@code DroolsBackend.voegIn}, {@code vuur},
 * {@code zetAfgeleideWaarden}): the answer carries the value a rule derived, and an evaluation fault that stands at the
 * fixpoint is a 422 that gives per fault its kind and uchar's sentence, never a stacktrace. Own material: one model of
 * finds with an entry point per rule set, so each request meets the fault it is about and no other, and one of a fold
 * over a to-many role, whose fault names the member the order of the list puts first, as in uchar's replay.
 */
class IngangHttpTest {

    private static final String BRON = """
        Objecttype de Vondst (mv: Vondsten)
            het gewicht Numeriek (getal met 2 decimalen);
            het aantal delen Numeriek (geheel getal);
            het deelgewicht Numeriek (getal met 2 decimalen);
            het vindersloon Numeriek (getal met 2 decimalen);
            het vondstjaar Numeriek (geheel getal);

        Regel deelgewicht van een vondst
            geldig altijd
                Het deelgewicht van een Vondst moet berekend worden als zijn gewicht gedeeld door zijn aantal delen.

        Regel loon voor een zware vondst
            geldig altijd
                Het vindersloon van een Vondst moet gesteld worden op 25,00
                indien zijn gewicht groter is dan 5,00.

        Regel loon voor een grote vondst
            geldig altijd
                Het vindersloon van een Vondst moet gesteld worden op 10,00
                indien zijn gewicht groter is dan 3,00.

        Regel vondstjaar van een vondst
            geldig altijd
                Het vondstjaar van een Vondst moet gesteld worden op Rekenjaar.
        """;

    private static final String DESCRIPTOR = """
        { "dienst": {"naam":"vondsten","versie":"1"},
          "entrypoints": [
            { "naam":"deelgewicht","pad":"deelgewicht",
              "scope": {"regelnamen": ["deelgewicht van een vondst"]},
              "invoer": ["Vondst"], "uitvoer": ["Vondst.deelgewicht"] },
            { "naam":"vindersloon","pad":"vindersloon",
              "scope": {"regelnamen": ["loon voor een zware vondst", "loon voor een grote vondst"]},
              "invoer": ["Vondst"], "uitvoer": ["Vondst.vindersloon"] },
            { "naam":"vondstjaar","pad":"vondstjaar",
              "scope": {"regelnamen": ["vondstjaar van een vondst"]},
              "invoer": ["Vondst"], "uitvoer": ["Vondst.vondstjaar"] },
            { "naam":"alles","pad":"alles",
              "scope": {"regelnamen": ["deelgewicht van een vondst", "loon voor een zware vondst",
                                       "loon voor een grote vondst", "vondstjaar van een vondst"]},
              "invoer": ["Vondst"], "uitvoer": ["Vondst.deelgewicht", "Vondst.vindersloon", "Vondst.vondstjaar"] } ],
          "parameters": {} }""";

    /** An own total without a clip over an attribute on a timeline, which reads the asOfDate-bucket scalar. */
    private static final String BEWARING_BRON = """
        Eenheidsysteem Munt
            de euro (mv: euros) EUR €

        Eenheidsysteem Bewaartijd
            de maand mnd

        Objecttype de Jutter (mv: Jutters) (bezield)
            de bewaarvergoeding per maand Numeriek (geheel getal) met eenheid €/mnd voor elke maand;
            het bewaargeld Numeriek (geheel getal) met eenheid €;

        Regel bewaargeld van een jutter
            geldig altijd
                Het bewaargeld van een Jutter moet berekend worden als het totaal van zijn bewaarvergoeding per maand.
        """;

    private static final String BEWARING_DESCRIPTOR = """
        { "dienst": {"naam":"bewaring","versie":"1"},
          "entrypoints": [ { "naam":"bewaargeld","pad":"bewaargeld",
            "scope": {"regelnamen": ["bewaargeld van een jutter"]},
            "invoer": ["Jutter"], "uitvoer": ["Jutter.bewaargeld"] } ],
          "parameters": {} }""";

    /** A fold over a to-many role whose filter divides by a member's deelnemers: where it divides by 0 for two
     *  members, the evaluation stops at the first in the order of the list (RUNTIME-SEMANTICS ch. 5.11). */
    private static final String VOUW_BRON = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de zware vondsten Numeriek (geheel getal);

        Objecttype de Vondst (mv: Vondsten)
            het gewicht Numeriek (geheel getal);
            de deelnemers Numeriek (geheel getal);

        Feittype aanmelding
            de aanmelder Jutter
            de vondst (mv: vondsten) Vondst
        Eén aanmelder heeft meerdere vondsten

        Regel zware vondsten
            geldig altijd
                De zware vondsten van een Jutter moet berekend worden als het aantal vondsten van de Jutter
                die aan de volgende voorwaarde voldoen: zijn gewicht gedeeld door zijn deelnemers is groter dan 2.
        """;

    private static final String VOUW_DESCRIPTOR = """
        { "dienst": {"naam":"vouw","versie":"1"},
          "entrypoints": [ { "naam":"zware vondsten","pad":"zware-vondsten",
            "scope": {"regelnamen": ["zware vondsten"]},
            "invoer": ["Jutter"], "uitvoer": ["Jutter.zware vondsten"] } ],
          "parameters": {} }""";

    private static DienstHttp dienst;
    private static DienstHttp vouwdienst;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        BeslisserKern kern = BeslisserKern.bouw(DESCRIPTOR, Map.of("vondsten.rgs", BRON), KernConfig.strikt(),
                Thread.currentThread().getContextClassLoader());
        dienst = DienstHttp.bind(kern, "127.0.0.1", 0).start();
        BeslisserKern vouw = BeslisserKern.bouw(VOUW_DESCRIPTOR, Map.of("vouw.rgs", VOUW_BRON), KernConfig.strikt(),
                Thread.currentThread().getContextClassLoader());
        vouwdienst = DienstHttp.bind(vouw, "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (dienst != null) dienst.stop(0);
        if (vouwdienst != null) vouwdienst.stop(0);
    }

    // ---------------- (c) an answer with a derived value ----------------

    @Test
    void het_antwoord_geeft_de_afgeleide_waarde() throws Exception {
        HttpResponse<String> resp = post("/deelgewicht", """
            {"instanties":[{"type":"Vondst","id":"V1","attributen":{"gewicht":6.00,"aantal delen":3}}]}""");
        assertEquals(200, resp.statusCode(), resp.body());
        JsonNode deelgewicht = vind(boom(resp.body()), "V1", "deelgewicht");
        assertNotNull(deelgewicht, resp.body());
        assertTrue(deelgewicht.isNumber(), resp.body());
        assertEquals(0, new BigDecimal("2.00").compareTo(deelgewicht.decimalValue()), resp.body());
    }

    // ---------------- (d) an evaluation fault per kind: 422 ----------------

    @Test
    void een_deling_door_nul_is_422_deling_door_nul() throws Exception {
        JsonNode body = evaluatiefout(post("/deelgewicht", """
            {"instanties":[{"type":"Vondst","id":"V1","attributen":{"gewicht":6.00,"aantal delen":0}}]}"""));
        assertEquals("de regels geven op deze invoer een evaluatiefout", body.get("fout").asText(), body.toString());
        assertEquals(List.of("DELING_DOOR_NUL"), soorten(body), body.toString());
        assertEquals("deling door nul in de regel \"deelgewicht van een vondst\": 6 gedeeld door 0 — een numerieke nul"
                + " als deler is een fout, geen leeg (RUNTIME-SEMANTICS ch. 1.4, punt 4)",
                body.at("/fouten/0/fout").asText(), body.toString());
    }

    @Test
    void een_rekendatum_die_ontbreekt_is_422_rekenfout() throws Exception {
        JsonNode body = evaluatiefout(post("/vondstjaar", """
            {"instanties":[{"type":"Vondst","id":"V1","attributen":{}}]}"""));
        assertEquals(List.of("REKENFOUT"), soorten(body), body.toString());
        assertTrue(body.at("/fouten/0/fout").asText().contains("vondstjaar van een vondst")
                && body.at("/fouten/0/fout").asText().contains("evaluation date"), body.toString());
    }

    @Test
    void twee_schrijvers_die_verschillen_zijn_422_schrijversconflict_met_het_id_uit_het_verzoek() throws Exception {
        JsonNode body = evaluatiefout(post("/vindersloon", """
            {"instanties":[{"type":"Vondst","id":"V1","attributen":{"gewicht":6.00}}]}"""));
        assertEquals(List.of("SCHRIJVERSCONFLICT"), soorten(body), body.toString());
        String zin = body.at("/fouten/0/fout").asText();
        assertTrue(zin.contains("\"loon voor een zware vondst\" schrijft 25") && zin.contains("\"loon voor een grote"
                + " vondst\" schrijft 10") && zin.contains("in het attribuut vindersloon van V1, een Vondst"), zin);
    }

    @Test
    void twee_fouten_tegelijk_zijn_een_422_met_een_element_per_fout() throws Exception {
        JsonNode body = evaluatiefout(post("/alles", """
            {"peildatum":"2026-03-15",
             "instanties":[{"type":"Vondst","id":"V1","attributen":{"gewicht":6.00,"aantal delen":0}}]}"""));
        assertEquals("de regels geven op deze invoer 2 evaluatiefouten", body.get("fout").asText(), body.toString());
        assertEquals(List.of("DELING_DOOR_NUL", "SCHRIJVERSCONFLICT"), soorten(body), body.toString());
        assertTrue(body.at("/fouten/0/fout").asText().startsWith("deling door nul in de regel \"deelgewicht van een"
                + " vondst\""), body.toString());
        assertTrue(body.at("/fouten/1/fout").asText().contains("van V1, een Vondst"), body.toString());
    }

    /** No request reaches an {@code Invoervormfout}: the HTTP route answers a request with a timeline with 400
     *  NIET_ONDERSTEUND, which this test asserts first. The translation of that fault into a 422 is therefore tested at
     *  the level of the core: the service's executor ({@link Uitvoerder}) gets a world whose bean holds a full timeline
     *  where the rule takes the asOfDate-bucket scalar, and {@link AntwoordBouwer#evaluatiefout} builds from the fault
     *  the body both adapters send with the 422. This is the strongest witness of this kind that exists. */
    @Test
    void een_tijdlijn_geeft_over_http_400_en_de_kern_vertaalt_een_invoervormfout_naar_de_422() throws Exception {
        BeslisserKern kern = BeslisserKern.bouw(BEWARING_DESCRIPTOR, Map.of("bewaring.rgs", BEWARING_BRON),
                KernConfig.strikt(), Thread.currentThread().getContextClassLoader());
        DienstHttp bewaring = DienstHttp.bind(kern, "127.0.0.1", 0).start();
        try {
            HttpResponse<String> resp = post(bewaring, "/bewaargeld", """
                {"peildatum":"2026-03-15",
                 "instanties":[{"type":"Jutter","id":"J1","attributen":{"bewaarvergoeding per maand":10}}]}""");
            assertEquals(400, resp.statusCode(), resp.body());
            assertEquals("NIET_ONDERSTEUND", boom(resp.body()).at("/schendingen/0/soort").asText(), resp.body());
        } finally {
            bewaring.stop(0);
        }

        BeslisserKern.Entrypoint ep = kern.entrypoint("bewaargeld");
        ClassLoader cl = ep.beanLoader();
        Object jutter = Class.forName(FactModelGenerator.PAKKET + ".Jutter", true, cl).getConstructor().newInstance();
        Object segment = Class.forName(FactModelGenerator.PAKKET + ".Segment", true, cl)
                .getConstructor(LocalDate.class, LocalDate.class, BigDecimal.class)
                .newInstance(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), new BigDecimal(10));
        jutter.getClass().getMethod("setBewaarvergoedingPerMaand", List.class).invoke(jutter, List.of(segment));
        Map<String, Object> beans = new LinkedHashMap<>();
        beans.put("J1", jutter);
        Evaluatiefout fout = assertThrows(Evaluatiefout.class, () -> new Uitvoerder(ep.build(), ep.defaultGlobals())
                .vuur(new Wereld(beans, Map.of(), LocalDate.of(2026, 3, 15), new RolSchrijver.Lijsten())));
        JsonNode body = AntwoordBouwer.evaluatiefout(fout);
        assertEquals("EVALUATIEFOUT", body.path("status").asText(), body.toString());
        assertEquals(List.of("INVOERVORMFOUT"), soorten(body), body.toString());
        String zin = body.at("/fouten/0/fout").asText();
        assertTrue(zin.startsWith("fout in de regel \"bewaargeld van een jutter\"") && zin.contains("\"J1\"")
                && zin.contains("supplies a full timeline"), zin);
    }

    // ---------------- the order of a to-many list: the member a fault of a fold names ----------------

    /** J1 writes its vondsten as V1, V3, V2, while the instances come as V1, V2, V3; the filter divides 9 by 0 for V3
     *  and 4 by 0 for V2. uchar's replay of that input stops at V3, the first in the written order, and so does the
     *  service; the order of the instances would name V2's 4. */
    @Test
    void een_lijst_onder_de_houder_noemt_het_falende_lid_in_de_volgorde_van_het_verzoek(@TempDir Path map)
            throws Exception {
        assertVouwfoutAlsReplay(map, """
            {"instanties":[{"type":"Jutter","id":"J1","rollen":{"vondsten":["V1","V3","V2"]}},
              {"type":"Vondst","id":"V1","attributen":{"gewicht":10,"deelnemers":2}},
              {"type":"Vondst","id":"V2","attributen":{"gewicht":4,"deelnemers":0}},
              {"type":"Vondst","id":"V3","attributen":{"gewicht":9,"deelnemers":0}}]}""", null);
    }

    /** V9 (4 by 0) and V10 (9 by 0) each name J1 as their aanmelder, in that order: the replay mirrors those links into
     *  J1's list by label in byte order (RUNTIME-SEMANTICS ch. 5.7a), V10 before V9, so 9, where the order of the
     *  instances gives 4. */
    @Test
    void een_gespiegelde_lijst_noemt_het_falende_lid_in_de_volgorde_van_de_ids(@TempDir Path map) throws Exception {
        assertVouwfoutAlsReplay(map, """
            {"instanties":[{"type":"Jutter","id":"J1"},
              {"type":"Vondst","id":"V9","attributen":{"gewicht":4,"deelnemers":0},"rollen":{"aanmelder":"J1"}},
              {"type":"Vondst","id":"V10","attributen":{"gewicht":9,"deelnemers":0},"rollen":{"aanmelder":"J1"}}]}""",
                null);
    }

    /** A relation of {@code relaties} sets the member's own field toward the holder, as a role the member writes does,
     *  so its member joins the list as a mirrored one: J1 writes V1, and the relations link V9 (4 by 0) and V10 (9 by
     *  0), which gives the order V1, V10, V9 and 9, as the replay gives on the case where V9 and V10 each write their
     *  aanmelder. A list of the written members alone would leave V9 and V10 out, which uchar's entry refuses. */
    @Test
    void een_lid_uit_relaties_staat_in_de_lijst_als_een_gespiegeld_lid(@TempDir Path map) throws Exception {
        assertVouwfoutAlsReplay(map, """
            {"instanties":[{"type":"Jutter","id":"J1","rollen":{"vondsten":["V1"]}},
              {"type":"Vondst","id":"V1","attributen":{"gewicht":10,"deelnemers":2}},
              {"type":"Vondst","id":"V9","attributen":{"gewicht":4,"deelnemers":0}},
              {"type":"Vondst","id":"V10","attributen":{"gewicht":9,"deelnemers":0}}],
             "relaties":[{"feittype":"aanmelding","rollen":{"aanmelder":"J1","vondst":"V9"}},
                         {"feittype":"aanmelding","rollen":{"aanmelder":"J1","vondst":"V10"}}]}""", """
            {"instanties":[{"type":"Jutter","id":"J1","rollen":{"vondsten":["V1"]}},
              {"type":"Vondst","id":"V1","attributen":{"gewicht":10,"deelnemers":2}},
              {"type":"Vondst","id":"V9","attributen":{"gewicht":4,"deelnemers":0},"rollen":{"aanmelder":"J1"}},
              {"type":"Vondst","id":"V10","attributen":{"gewicht":9,"deelnemers":0},"rollen":{"aanmelder":"J1"}}]}""");
    }

    /** V1 names J1 as its aanmelder by its own role, and a relation of {@code relaties} links it to J2: the to-one field
     *  aanmelder gets a second, different target. The service refuses that with 400 VERKEERD_TYPE on the role of the
     *  relation that names J2, as it refuses a second target among the inline roles ({@code RolSchrijver}). Before, the
     *  relation wrote over the first link and the list of J1 kept V1, which uchar's entry refused (500). */
    @Test
    void een_tweede_ander_doel_via_relaties_is_400_verkeerd_type() throws Exception {
        HttpResponse<String> resp = post(vouwdienst, "/zware-vondsten", """
            {"instanties":[{"type":"Jutter","id":"J1"},{"type":"Jutter","id":"J2"},
              {"type":"Vondst","id":"V1","attributen":{"gewicht":10,"deelnemers":2},"rollen":{"aanmelder":"J1"}}],
             "relaties":[{"feittype":"aanmelding","rollen":{"aanmelder":"J2","vondst":"V1"}}]}""");
        assertEquals(400, resp.statusCode(), resp.body());
        JsonNode body = boom(resp.body());
        assertEquals(1, body.path("schendingen").size(), resp.body());
        assertEquals("VERKEERD_TYPE", body.at("/schendingen/0/soort").asText(), resp.body());
        assertEquals("relaties[0].rollen.aanmelder", body.at("/schendingen/0/veld").asText(), resp.body());
        assertEquals("de naar-één rol 'aanmelder' krijgt een tweede, ander doel", body.at("/schendingen/0/fout").asText(),
                resp.body());
    }

    // ---------------- helpers ----------------

    /** The 422 of {@code verzoek} on the fold names the fault uchar's replay names on the same input written as a case
     *  ({@code alsCase} where the request carries {@code relaties}, which a case does not have; else {@code verzoek}),
     *  and that fault is the division of 9: the replay names the member the order of its list puts first. */
    private static void assertVouwfoutAlsReplay(Path map, String verzoek, String alsCase) throws Exception {
        String replay = replayFout(map, alsCase != null ? alsCase : verzoek);
        assertTrue(replay.contains("\"zware vondsten\": 9 gedeeld door 0"), replay);
        JsonNode body = evaluatiefout(post(vouwdienst, "/zware-vondsten", verzoek));
        assertEquals(List.of("DELING_DOOR_NUL"), soorten(body), body.toString());
        assertEquals(replay, body.at("/fouten/0/fout").asText(), body.toString());
    }

    /** The sentence of the fault uchar's replay ({@code CaseRunner.runGolden}) throws on {@code verzoek} as a case of the
     *  fold's document: the reverse of how the replay gate writes a case as a request, an instance per instance with its
     *  numbers and its roles, in the order of the request. */
    private static String replayFout(Path map, String verzoek) throws Exception {
        RegelSpraakModel model = Resolver.resolve(VOUW_BRON);
        assertEquals(List.of(), model.errors());
        Path ir = map.resolve("ir.json");
        Files.writeString(ir, IrEmitter.emit(model), StandardCharsets.UTF_8);
        ObjectNode zaak = DienstHttp.MAPPER.createObjectNode();
        zaak.put("format", "uchar-case-v0");
        zaak.putNull("asOfDate");
        ObjectNode input = zaak.putObject("input");
        ArrayNode instances = input.putArray("instances");
        for (JsonNode i : boom(verzoek).get("instanties")) {
            ObjectNode instance = instances.addObject();
            instance.put("label", i.get("id").asText());
            instance.put("objectType", i.get("type").asText());
            ObjectNode attributes = instance.putObject("attributes");
            for (Map.Entry<String, JsonNode> a : i.path("attributen").properties())
                attributes.putObject(a.getKey()).put("number", a.getValue().asText());
            ObjectNode roles = instance.putObject("roles");
            for (Map.Entry<String, JsonNode> r : i.path("rollen").properties()) {
                ArrayNode doelen = roles.putArray(r.getKey());
                if (r.getValue().isArray()) r.getValue().forEach(doelen::add);
                else doelen.add(r.getValue());
            }
            instance.putArray("characteristics");
        }
        input.putObject("parameters");
        zaak.putArray("expected").addObject().put("kind", "consistent");
        Path casePad = map.resolve("case.json");
        Files.writeString(casePad, zaak.toString(), StandardCharsets.UTF_8);
        return assertThrows(Breuk.DelingDoorNul.class, () -> CaseRunner.runGolden(ir, List.of(casePad))).getMessage();
    }

    /** The body of a 422: status EVALUATIEFOUT, a sentence, and per fault its kind and uchar's sentence, without a
     *  stacktrace. */
    private static JsonNode evaluatiefout(HttpResponse<String> resp) throws Exception {
        assertEquals(422, resp.statusCode(), resp.body());
        JsonNode body = boom(resp.body());
        assertEquals("EVALUATIEFOUT", body.path("status").asText(), resp.body());
        assertTrue(body.path("fouten").isArray() && !body.path("fouten").isEmpty(), resp.body());
        assertEquals(List.of("fout", "fouten", "status"), velden(body), resp.body());
        for (JsonNode f : body.get("fouten")) assertEquals(List.of("fout", "soort"), velden(f), resp.body());
        assertFalse(resp.body().contains("Exception") || resp.body().contains("\tat "), "geen stacktrace: " + resp.body());
        return body;
    }

    private static List<String> soorten(JsonNode body) {
        return body.get("fouten").valueStream().map(f -> f.get("soort").asText()).toList();
    }

    private static List<String> velden(JsonNode n) {
        List<String> uit = new java.util.ArrayList<>();
        n.fieldNames().forEachRemaining(uit::add);
        return uit.stream().sorted().toList();
    }

    private static HttpResponse<String> post(String pad, String body) throws Exception {
        return post(dienst, pad, body);
    }

    private static HttpResponse<String> post(DienstHttp naar, String pad, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + naar.poort() + pad))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonNode boom(String json) throws Exception {
        return DienstHttp.MAPPER.readTree(json);
    }

    private static JsonNode vind(JsonNode antwoord, String id, String attribuut) {
        for (JsonNode inst : antwoord.get("instanties"))
            if (id.equals(inst.path("id").asText())) return inst.path("attributen").get(attribuut);
        return null;
    }
}
