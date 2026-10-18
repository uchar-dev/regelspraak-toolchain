package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.cases.CaseInput;
import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.emit.IrEmitter;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.runtime.Breuk;
import dev.uchar.regelspraak.toolchain.service.AnswerBuilder;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.EvaluationFault;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.Executor;
import dev.uchar.regelspraak.toolchain.service.World;
import dev.uchar.regelspraak.transpiler.tms.EvaluationFaults;

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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service fills, fires and reads every session through uchar's entry ({@code DroolsBackend.insert}, {@code fire},
 * {@code fixpointOf}, {@code setDerivedValues}): the answer carries the value a rule derived, and every evaluation fault
 * that stands at the fixpoint is a 422 that gives per fault its kind and uchar's sentence, never a stacktrace. Own
 * material: one model of finds with an entry point per rule set, so each request meets the fault it is about and no
 * other, one of a fold over a to-many role, whose fault names the member the order of the list puts first, as in
 * uchar's replay, the model of uchar's witness of a fact creation that gives a to-one role a second filling, a fault of
 * another type, and that of a reciprocal role written on a subtype of its filler.
 */
class EntryHttpTest {

    private static final String SOURCE = """
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
    private static final String RETENTION_SOURCE = """
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
    private static final String FOLD_SOURCE = """
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

    private static final String FOLD_DESCRIPTOR = """
        { "dienst": {"naam":"vouw","versie":"1"},
          "entrypoints": [ { "naam":"zware vondsten","pad":"zware-vondsten",
            "scope": {"regelnamen": ["zware vondsten"]},
            "invoer": ["Jutter"], "uitvoer": ["Jutter.zware vondsten"] } ],
          "parameters": {} }""";

    /** The model of uchar's witness of a second filling ({@code TmsCreatieWitnessTest}, document D): the fact creation
     *  makes every geworven jutter of the kustwacht of a Haven a havenjutter of that Haven, and a Jutter has one haven.
     *  The division of ligplaatsdeel is the other fault of the mixed request. */
    private static final String FILLING_SOURCE = """
        Objecttype de Kustwacht (mv: Kustwachten)

        Objecttype de Haven (mv: Havens)
            het jutteraantal Numeriek (geheel getal);
            de ligplaatsen Numeriek (geheel getal);
            het ligplaatsdeel Numeriek (geheel getal);

        Objecttype de Jutter (mv: Jutters) (bezield)

        Feittype bewaking
            de kustwacht Kustwacht
            de bewaakte haven (mv: bewaakte havens) Haven
        Eén kustwacht bewaakt meerdere bewaakte havens

        Feittype werving
            de werver Kustwacht
            de geworven jutter (mv: geworven jutters) Jutter
        Eén werver heeft meerdere geworven jutters

        Feittype havenlid
            de haven Haven
            de havenjutter (mv: havenjutters) Jutter
        Eén haven heeft meerdere havenjutters

        Regel jutteraantal van een haven
            geldig altijd
                Het jutteraantal van een Haven moet berekend worden als het aantal havenjutters van de Haven.

        Regel werving van havenjutters
            geldig altijd
                Een havenjutter van een Haven is een geworven jutter van de kustwacht van de Haven.

        Regel ligplaatsdeel van een haven
            geldig altijd
                Het ligplaatsdeel van een Haven moet berekend worden als 100 gedeeld door zijn ligplaatsen.
        """;

    private static final String FILLING_DESCRIPTOR = """
        { "dienst": {"naam":"vulling","versie":"1"},
          "entrypoints": [
            { "naam":"vulling","pad":"vulling",
              "scope": {"regelnamen": ["jutteraantal van een haven", "werving van havenjutters"]},
              "invoer": ["Kustwacht", "Haven", "Jutter"], "uitvoer": ["Haven.jutteraantal"] },
            { "naam":"mengsel","pad":"mengsel",
              "scope": {"regelnamen": ["jutteraantal van een haven", "werving van havenjutters",
                                       "ligplaatsdeel van een haven"]},
              "invoer": ["Kustwacht", "Haven", "Jutter"], "uitvoer": ["Haven.jutteraantal", "Haven.ligplaatsdeel"] } ],
          "parameters": {} }""";

    /** uchar's case D2: K1 guards H1 and H2 and recruits J1, so J1 would be a havenjutter of both. */
    private static final String FILLING = """
        {"instanties":[{"type":"Kustwacht","id":"K1",
          "rollen":{"bewaakte havens":["H1","H2"],"geworven jutters":["J1"]}},
          {"type":"Haven","id":"H1"},{"type":"Haven","id":"H2"},{"type":"Jutter","id":"J1"}]}""";

    /** D2, with H1 at 0 ligplaatsen and H2 at 4. */
    private static final String MENGSEL = """
        {"instanties":[{"type":"Kustwacht","id":"K1",
          "rollen":{"bewaakte havens":["H1","H2"],"geworven jutters":["J1"]}},
          {"type":"Haven","id":"H1","attributen":{"ligplaatsen":0}},
          {"type":"Haven","id":"H2","attributen":{"ligplaatsen":4}},{"type":"Jutter","id":"J1"}]}""";

    /** The model of uchar's {@code SubtypeRolvullerWitnessTest.de_wederkerige_rol_spiegelt_ook_voor_een_subtype},
     *  without the rest of its golden: a reciprocal role filled by Vondst, and Wrakvondst a subtype of Vondst. */
    private static final String MAAT_SOURCE = """
        Objecttype de Vondst (mv: Vondsten)
            de waarde Numeriek (geheel getal);
            de maatwaarde Numeriek (geheel getal);

        Objecttype de Wrakvondst is een Vondst (mv: Wrakvondsten)

        Wederkerig feittype vondstpaar
            de maat (mv: maten) Vondst
        Eén maat heeft één maat

        Regel maatwaarde van een vondst
            geldig altijd
                De maatwaarde van een Vondst moet gesteld worden op de waarde van de maat van de Vondst.
        """;

    private static final String MAAT_DESCRIPTOR = """
        { "dienst": {"naam":"maat","versie":"1"},
          "entrypoints": [ { "naam":"maatwaarde","pad":"maatwaarde",
            "scope": {"regelnamen": ["maatwaarde van een vondst"]},
            "invoer": ["Vondst", "Wrakvondst"], "uitvoer": ["Vondst.maatwaarde"] } ],
          "parameters": {} }""";

    /** The case of uchar's witness: W, a Wrakvondst, writes the one link of the reciprocal role. */
    private static final String MAAT_CASE = """
        { "format": "uchar-case-v0", "asOfDate": null,
          "input": { "instances": [
              { "label": "V", "objectType": "Vondst", "attributes": { "waarde": { "number": "2" } },
                "roles": {}, "characteristics": [] },
              { "label": "W", "objectType": "Wrakvondst", "attributes": { "waarde": { "number": "5" } },
                "roles": { "maat": [ "V" ] }, "characteristics": [] } ], "parameters": {} },
          "expected": [
              { "kind": "attributeValue", "label": "V", "attribute": "maatwaarde", "value": { "number": "5" } },
              { "kind": "attributeValue", "label": "W", "attribute": "maatwaarde", "value": { "number": "2" } } ] }""";

    private static ServiceHttp service;
    private static ServiceHttp foldService;
    private static ServiceHttp fillingService;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        DecisionCore core = DecisionCore.assemble(DESCRIPTOR, Map.of("vondsten.rgs", SOURCE), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
        service = ServiceHttp.bind(core, "127.0.0.1", 0).start();
        DecisionCore fold = DecisionCore.assemble(FOLD_DESCRIPTOR, Map.of("vouw.rgs", FOLD_SOURCE), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
        foldService = ServiceHttp.bind(fold, "127.0.0.1", 0).start();
        DecisionCore filling = DecisionCore.assemble(FILLING_DESCRIPTOR, Map.of("vulling.rgs", FILLING_SOURCE),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
        fillingService = ServiceHttp.bind(filling, "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (service != null) service.stop(0);
        if (foldService != null) foldService.stop(0);
        if (fillingService != null) fillingService.stop(0);
    }

    // ---------------- (c) an answer with a derived value ----------------

    @Test
    void the_answer_gives_the_derived_value() throws Exception {
        HttpResponse<String> resp = post("/deelgewicht", """
            {"instanties":[{"type":"Vondst","id":"V1","attributen":{"gewicht":6.00,"aantal delen":3}}]}""");
        assertEquals(200, resp.statusCode(), resp.body());
        JsonNode deelgewicht = find(tree(resp.body()), "V1", "deelgewicht");
        assertNotNull(deelgewicht, resp.body());
        assertTrue(deelgewicht.isNumber(), resp.body());
        assertEquals(0, new BigDecimal("2.00").compareTo(deelgewicht.decimalValue()), resp.body());
    }

    // ---------------- (d) an evaluation fault per kind: 422 ----------------

    @Test
    void a_division_by_zero_is_422_division_by_zero() throws Exception {
        JsonNode body = evaluationFault(post("/deelgewicht", """
            {"instanties":[{"type":"Vondst","id":"V1","attributen":{"gewicht":6.00,"aantal delen":0}}]}"""));
        assertEquals("de regels geven op deze invoer een evaluatiefout", body.get("fout").asText(), body.toString());
        assertEquals(List.of("DELING_DOOR_NUL"), kinds(body), body.toString());
        assertEquals("deling door nul in de regel \"deelgewicht van een vondst\": 6 gedeeld door 0 — een numerieke nul"
                + " als deler is een fout, geen leeg (RUNTIME-SEMANTICS ch. 1.4, punt 4)",
                body.at("/fouten/0/fout").asText(), body.toString());
    }

    @Test
    void a_missing_evaluation_date_is_422_compute_fault() throws Exception {
        JsonNode body = evaluationFault(post("/vondstjaar", """
            {"instanties":[{"type":"Vondst","id":"V1","attributen":{}}]}"""));
        assertEquals(List.of("REKENFOUT"), kinds(body), body.toString());
        assertTrue(body.at("/fouten/0/fout").asText().contains("vondstjaar van een vondst")
                && body.at("/fouten/0/fout").asText().contains("evaluation date"), body.toString());
    }

    @Test
    void two_writers_that_differ_are_422_writers_conflict_with_the_id_from_the_request() throws Exception {
        JsonNode body = evaluationFault(post("/vindersloon", """
            {"instanties":[{"type":"Vondst","id":"V1","attributen":{"gewicht":6.00}}]}"""));
        assertEquals(List.of("SCHRIJVERSCONFLICT"), kinds(body), body.toString());
        String sentence = body.at("/fouten/0/fout").asText();
        assertTrue(sentence.contains("\"loon voor een zware vondst\" schrijft 25") && sentence.contains("\"loon voor een grote"
                + " vondst\" schrijft 10") && sentence.contains("in het attribuut vindersloon van V1, een Vondst"), sentence);
    }

    @Test
    void two_errors_at_once_are_a_422_with_one_element_per_error() throws Exception {
        JsonNode body = evaluationFault(post("/alles", """
            {"peildatum":"2026-03-15",
             "instanties":[{"type":"Vondst","id":"V1","attributen":{"gewicht":6.00,"aantal delen":0}}]}"""));
        assertEquals("de regels geven op deze invoer 2 evaluatiefouten", body.get("fout").asText(), body.toString());
        assertEquals(List.of("DELING_DOOR_NUL", "SCHRIJVERSCONFLICT"), kinds(body), body.toString());
        assertTrue(body.at("/fouten/0/fout").asText().startsWith("deling door nul in de regel \"deelgewicht van een"
                + " vondst\""), body.toString());
        assertTrue(body.at("/fouten/1/fout").asText().contains("van V1, een Vondst"), body.toString());
    }

    /** No request reaches an {@code InputFormFault}: the HTTP route answers a request with a timeline with 400
     *  NIET_ONDERSTEUND, which this test asserts first. The translation of that fault into a 422 is therefore tested at
     *  the level of the core: the service's executor ({@link Executor}) gets a world whose bean holds a full timeline
     *  where the rule takes the asOfDate-bucket scalar, and {@link AnswerBuilder#evaluationFault} builds from the fault
     *  the body both adapters send with the 422. This is the strongest witness of this kind that exists. */
    @Test
    void a_timeline_gives_over_http_400_and_the_core_translates_an_input_form_error_to_the_422() throws Exception {
        DecisionCore core = DecisionCore.assemble(BEWARING_DESCRIPTOR, Map.of("bewaring.rgs", RETENTION_SOURCE),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
        ServiceHttp bewaring = ServiceHttp.bind(core, "127.0.0.1", 0).start();
        try {
            HttpResponse<String> resp = post(bewaring, "/bewaargeld", """
                {"peildatum":"2026-03-15",
                 "instanties":[{"type":"Jutter","id":"J1","attributen":{"bewaarvergoeding per maand":10}}]}""");
            assertEquals(400, resp.statusCode(), resp.body());
            assertEquals("NIET_ONDERSTEUND", tree(resp.body()).at("/schendingen/0/soort").asText(), resp.body());
        } finally {
            bewaring.stop(0);
        }

        DecisionCore.Entrypoint ep = core.entrypoint("bewaargeld");
        CaseInput inputSection = CaseInput.van(ep.build(), tree("""
            {"asOfDate":"2026-03-15","input":{"instances":[{"label":"J1","objectType":"Jutter","attributes":
              {"bewaarvergoeding per maand":{"timeline":[{"from":"2026-01-01","to":"2027-01-01","value":{"number":"10"}}]}}}]}}"""));
        EvaluationFault error = assertThrows(EvaluationFault.class, () -> new Executor(ep.build(), ep.defaultGlobals())
                .fire(new World(inputSection, Map.of(), LocalDate.of(2026, 3, 15))));
        JsonNode body = AnswerBuilder.evaluationFault(error);
        assertEquals("EVALUATIEFOUT", body.path("status").asText(), body.toString());
        assertEquals(List.of("INVOERVORMFOUT"), kinds(body), body.toString());
        String sentence = body.at("/fouten/0/fout").asText();
        assertTrue(sentence.startsWith("fout in de regel \"bewaargeld van een jutter\"") && sentence.contains("\"J1\"")
                && sentence.contains("supplies a full timeline"), sentence);
    }

    // ---------------- a fault at the fixpoint of another type: ANDERE ----------------

    /** uchar's case D2 ({@code TmsCreatieWitnessTest.een_tweede_vulling_van_een_naar_een_rol_is_een_fout}): the fact
     *  creation gives J1 two havens, and uchar puts that fault at the fixpoint with a plain
     *  {@code IllegalStateException}, outside the four types its README names. It is an evaluation fault by its place
     *  there, so the 422 gives it as ANDERE, with the sentence uchar's replay gives on the same input. */
    @Test
    void a_second_filling_from_a_to_a_role_is_422_other(@TempDir Path map) throws Exception {
        List<String> replay = replayFaults(map, FILLING_SOURCE, FILLING);
        assertEquals(1, replay.size(), replay::toString);
        assertTrue(replay.get(0).startsWith("de naar-één-rol \"haven\" van een Jutter heeft twee verschillende"
                + " vullingen"), replay::toString);
        JsonNode body = evaluationFault(post(fillingService, "/vulling", FILLING));
        assertEquals("de regels geven op deze invoer een evaluatiefout", body.get("fout").asText(), body.toString());
        assertEquals(List.of("ANDERE"), kinds(body), body.toString());
        assertEquals(replay, sentences(body), body.toString());
    }

    /** The second filling of D2 beside a division by a numeric zero (H1 has 0 ligplaatsen): the 422 has an element
     *  for each, in the order of uchar's faults, which is by the name of the form ("havenlid", the fact type of the
     *  filling, before "ligplaatsdeel van een haven") and not by kind. */
    @Test
    void a_second_filling_beside_a_division_by_zero_is_a_422_with_both_in_the_uchar_order(@TempDir Path map)
            throws Exception {
        List<String> replay = replayFaults(map, FILLING_SOURCE, MENGSEL);
        assertEquals(2, replay.size(), replay::toString);
        JsonNode body = evaluationFault(post(fillingService, "/mengsel", MENGSEL));
        assertEquals("de regels geven op deze invoer 2 evaluatiefouten", body.get("fout").asText(), body.toString());
        assertEquals(List.of("ANDERE", "DELING_DOOR_NUL"), kinds(body), body.toString());
        assertEquals(replay, sentences(body), body.toString());
    }

    // ---------------- the order of a to-many list: the member a fault of a fold names ----------------

    /** J1 writes its vondsten as V1, V3, V2, while the instances come as V1, V2, V3; the filter divides 9 by 0 for V3
     *  and 4 by 0 for V2. uchar's replay of that input stops at V3, the first in the written order, and so does the
     *  service; the order of the instances would name V2's 4. */
    @Test
    void a_list_under_the_houder_names_the_failing_member_in_the_order_of_the_request(@TempDir Path map)
            throws Exception {
        assertFoldFaultAsReplay(map, """
            {"instanties":[{"type":"Jutter","id":"J1","rollen":{"vondsten":["V1","V3","V2"]}},
              {"type":"Vondst","id":"V1","attributen":{"gewicht":10,"deelnemers":2}},
              {"type":"Vondst","id":"V2","attributen":{"gewicht":4,"deelnemers":0}},
              {"type":"Vondst","id":"V3","attributen":{"gewicht":9,"deelnemers":0}}]}""", null);
    }

    /** V9 (4 by 0) and V10 (9 by 0) each name J1 as their aanmelder, in that order: the replay mirrors those links into
     *  J1's list by label in byte order (RUNTIME-SEMANTICS ch. 5.7a), V10 before V9, so 9, where the order of the
     *  instances gives 4. */
    @Test
    void a_mirrored_list_names_the_failing_member_in_the_order_of_the_ids(@TempDir Path map) throws Exception {
        assertFoldFaultAsReplay(map, """
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
    void a_member_from_relations_is_in_the_list_as_a_mirrored_member(@TempDir Path map) throws Exception {
        assertFoldFaultAsReplay(map, """
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
     *  relation that names J2, as it refuses a second target among the inline roles (uchar's {@code CaseInput}). Before, the
     *  relation wrote over the first link and the list of J1 kept V1, which uchar's entry refused (500). */
    @Test
    void a_second_other_target_via_relations_is_400_wrong_type() throws Exception {
        HttpResponse<String> resp = post(foldService, "/zware-vondsten", """
            {"instanties":[{"type":"Jutter","id":"J1"},{"type":"Jutter","id":"J2"},
              {"type":"Vondst","id":"V1","attributen":{"gewicht":10,"deelnemers":2},"rollen":{"aanmelder":"J1"}}],
             "relaties":[{"feittype":"aanmelding","rollen":{"aanmelder":"J2","vondst":"V1"}}]}""");
        assertEquals(400, resp.statusCode(), resp.body());
        JsonNode body = tree(resp.body());
        assertEquals(1, body.path("schendingen").size(), resp.body());
        assertEquals("VERKEERD_TYPE", body.at("/schendingen/0/soort").asText(), resp.body());
        assertEquals("relaties[0].rollen.aanmelder", body.at("/schendingen/0/veld").asText(), resp.body());
        assertEquals("to-one rol 'aanmelder' krijgt een tweede, ander doel — ref weigert die case bij het laden (WF-013)",
                body.at("/schendingen/0/fout").asText(), resp.body());
    }

    // ---------------- a reciprocal role written on a subtype of its filler ----------------

    /** W, a Wrakvondst, names V as its maat, and one link of a reciprocal role holds both ways (§13.3.9.2): uchar's
     *  CaseInput asks the reciprocal role along the chain of W's types, as uchar's replay does, so V reads W's waarde.
     *  Asked of W's own type alone, the reverse leg stayed unwritten and V's maatwaarde empty, in a 200, where uchar's
     *  replay of the same case plays with 5. */
    @Test
    void a_reciprocal_role_on_a_subtype_applies_in_both_directions(@TempDir Path map) throws Exception {
        RegelSpraakModel model = Resolver.resolve(MAAT_SOURCE);
        assertEquals(List.of(), model.errors());
        Path ir = map.resolve("ir.json");
        Files.writeString(ir, IrEmitter.emit(model), StandardCharsets.UTF_8);
        Path testCase = map.resolve("case.json");
        Files.writeString(testCase, MAAT_CASE, StandardCharsets.UTF_8);
        CaseRunner.Result replay = CaseRunner.runGolden(ir, List.of(testCase));
        assertEquals("PLAYED", replay.status(), replay::toString);

        DecisionCore core = DecisionCore.assemble(MAAT_DESCRIPTOR, Map.of("maat.rgs", MAAT_SOURCE), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
        ServiceHttp maat = ServiceHttp.bind(core, "127.0.0.1", 0).start();
        try {
            HttpResponse<String> resp = post(maat, "/maatwaarde", """
                {"instanties":[{"type":"Vondst","id":"V","attributen":{"waarde":2}},
                  {"type":"Wrakvondst","id":"W","attributen":{"waarde":5},"rollen":{"maat":"V"}}]}""");
            assertEquals(200, resp.statusCode(), resp.body());
            JsonNode answer = tree(resp.body());
            assertEquals("5", String.valueOf(find(answer, "V", "maatwaarde")), resp.body());
            assertEquals("2", String.valueOf(find(answer, "W", "maatwaarde")), resp.body());
        } finally {
            maat.stop(0);
        }
    }

    // ---------------- helpers ----------------

    /** The 422 of {@code verzoek} on the fold names the fault uchar's replay names on the same input written as a case
     *  ({@code asCase} where the request carries {@code relaties}, which a case does not have; else {@code verzoek}),
     *  and that fault is the division of 9: the replay names the member the order of its list puts first. */
    private static void assertFoldFaultAsReplay(Path map, String request, String asCase) throws Exception {
        String replay = replayFault(map, asCase != null ? asCase : request);
        assertTrue(replay.contains("\"zware vondsten\": 9 gedeeld door 0"), replay);
        JsonNode body = evaluationFault(post(foldService, "/zware-vondsten", request));
        assertEquals(List.of("DELING_DOOR_NUL"), kinds(body), body.toString());
        assertEquals(replay, body.at("/fouten/0/fout").asText(), body.toString());
    }

    /** The sentence of the fault uchar's replay ({@code CaseRunner.runGolden}) throws on {@code verzoek} as a case of the
     *  fold's document. */
    private static String replayFault(Path map, String request) throws Exception {
        RuntimeException error = replay(map, FOLD_SOURCE, request);
        assertInstanceOf(Breuk.DelingDoorNul.class, error, error::toString);
        return error.getMessage();
    }

    /** The sentences of the faults uchar's replay throws on {@code verzoek} as a case of the document of {@code source},
     *  in its order: one fault as its own exception, two or more as {@code EvaluationFaults}. */
    private static List<String> replayFaults(Path map, String source, String request) throws Exception {
        RuntimeException error = replay(map, source, request);
        if (error instanceof EvaluationFaults more) return more.faults().stream().map(Throwable::getMessage).toList();
        return List.of(error.getMessage());
    }

    /** What uchar's replay ({@code CaseRunner.runGolden}) throws on {@code verzoek} as a case of the document of
     *  {@code source}: the reverse of how the replay gate writes a case as a request, an instance per instance with its
     *  numbers and its roles, in the order of the request. */
    private static RuntimeException replay(Path map, String source, String request) throws Exception {
        RegelSpraakModel model = Resolver.resolve(source);
        assertEquals(List.of(), model.errors());
        Path ir = map.resolve("ir.json");
        Files.writeString(ir, IrEmitter.emit(model), StandardCharsets.UTF_8);
        ObjectNode testCase = ServiceHttp.MAPPER.createObjectNode();
        testCase.put("format", "uchar-case-v0");
        testCase.putNull("asOfDate");
        ObjectNode input = testCase.putObject("input");
        ArrayNode instances = input.putArray("instances");
        for (JsonNode i : tree(request).get("instanties")) {
            ObjectNode instance = instances.addObject();
            instance.put("label", i.get("id").asText());
            instance.put("objectType", i.get("type").asText());
            ObjectNode attributes = instance.putObject("attributes");
            for (Map.Entry<String, JsonNode> a : i.path("attributen").properties())
                attributes.putObject(a.getKey()).put("number", a.getValue().asText());
            ObjectNode roles = instance.putObject("roles");
            for (Map.Entry<String, JsonNode> r : i.path("rollen").properties()) {
                ArrayNode targets = roles.putArray(r.getKey());
                if (r.getValue().isArray()) r.getValue().forEach(targets::add);
                else targets.add(r.getValue());
            }
            instance.putArray("characteristics");
        }
        input.putObject("parameters");
        testCase.putArray("expected").addObject().put("kind", "consistent");
        Path casePath = map.resolve("case.json");
        Files.writeString(casePath, testCase.toString(), StandardCharsets.UTF_8);
        return assertThrows(RuntimeException.class, () -> CaseRunner.runGolden(ir, List.of(casePath)));
    }

    /** The body of a 422: status EVALUATIEFOUT, a sentence, and per fault its kind and uchar's sentence, without a
     *  stacktrace. */
    private static JsonNode evaluationFault(HttpResponse<String> resp) throws Exception {
        assertEquals(422, resp.statusCode(), resp.body());
        JsonNode body = tree(resp.body());
        assertEquals("EVALUATIEFOUT", body.path("status").asText(), resp.body());
        assertTrue(body.path("fouten").isArray() && !body.path("fouten").isEmpty(), resp.body());
        assertEquals(List.of("fout", "fouten", "status"), fields(body), resp.body());
        for (JsonNode f : body.get("fouten")) assertEquals(List.of("fout", "soort"), fields(f), resp.body());
        assertFalse(resp.body().contains("Exception") || resp.body().contains("\tat "), "geen stacktrace: " + resp.body());
        return body;
    }

    private static List<String> kinds(JsonNode body) {
        return body.get("fouten").valueStream().map(f -> f.get("soort").asText()).toList();
    }

    private static List<String> sentences(JsonNode body) {
        return body.get("fouten").valueStream().map(f -> f.get("fout").asText()).toList();
    }

    private static List<String> fields(JsonNode n) {
        List<String> sink = new java.util.ArrayList<>();
        n.fieldNames().forEachRemaining(sink::add);
        return sink.stream().sorted().toList();
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return post(service, path, body);
    }

    private static HttpResponse<String> post(ServiceHttp to, String path, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + to.port() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonNode tree(String json) throws Exception {
        return ServiceHttp.MAPPER.readTree(json);
    }

    private static JsonNode find(JsonNode answer, String id, String attribute) {
        for (JsonNode inst : answer.get("instanties"))
            if (id.equals(inst.path("id").asText())) return inst.path("attributen").get(attribute);
        return null;
    }
}
